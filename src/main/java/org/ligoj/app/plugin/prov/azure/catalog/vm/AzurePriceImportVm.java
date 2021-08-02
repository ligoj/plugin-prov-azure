/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.vm;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractVmAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 * TODO Basic tiers does not support Load Balancing<br>
 */
@Component
@Slf4j
public class AzurePriceImportVm extends AbstractVmAzureImport<ProvInstanceType> {

	private static final String STEP_COMPUTE = "vm-%s";

	/**
	 * Configuration key used for enabled instance type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_ITYPE = ProvAzurePluginResource.KEY + ":instance-type";

	/**
	 * Configuration key used for enabled OS pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_OS = ProvAzurePluginResource.KEY + ":os";

	private Set<String> dedicatedTypes = new HashSet<>();

	/**
	 * Install or update prices.
	 *
	 * @throws IOException When prices cannot be remotely read.
	 */
	@Override
	public void install(final UpdateContext context) throws IOException {
		nextStep(context, String.format(STEP_COMPUTE, "initialize"));
		final var node = context.getNode();
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*"), Pattern.CASE_INSENSITIVE));
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstanceType::getCode, Function.identity())));
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity())));
		context.setPrevious(ipRepository.findAllBy("term.node", node).stream()
				.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
		installComputePrices(context);

		// Purge
		purgePrices(context, context.getPrevious(), ipRepository, qiRepository);
		log.info("Azure Database import finished : {} prices", context.getPrices().size());
	}

	private String getVmApi() {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES_V3) + "/virtual-machines/calculator/";
	}

	/**
	 * Install Pay-as-you-Go, one year, three years compute prices from the JSON file provided by Azure for the given
	 * category.
	 *
	 * @param context The update context.
	 */
	private void installComputePrices(final UpdateContext context) throws IOException {
		// Fetch the remote prices stream and build the prices object
		nextStep(context, String.format(STEP_COMPUTE, "retrieve-catalog"));
		try (var curl = new CurlProcessor()) {
			final var rawJson = StringUtils.defaultString(curl.get(getVmApi()), "{}");
			final var prices = objectMapper.readValue(rawJson, ComputePrices.class);
			nextStep(context, String.format(STEP_COMPUTE, "parse-catalog"));
			commonPreparation(context, prices);
			prices.getSoftwareLicenses().forEach(n -> prices.getSoftwareById().put(n.getId(), n.getName()));
			prices.getSizesOneYear().forEach(n -> context.getSizesById().put(n.getId(), n.getName()));
			prices.getSizesThreeYear().forEach(n -> context.getSizesById().put(n.getId(), n.getName()));
			prices.getSizesFiveYear().forEach(n -> context.getSizesById().put(n.getId(), n.getName()));
			prices.getSizesPayGo().forEach(n -> context.getSizesById().put(n.getId(), n.getName()));

			// Parse offers
			prices.getOffers().entrySet().stream().forEach(e -> parseOffer(context, e.getKey(), e.getValue()));

			// Install SKUs and install prices
			nextStep(context, String.format(STEP_COMPUTE, "install"));
			prices.getSkus().forEach((sku, termMappings) -> installSku(context, prices, sku, termMappings));
		}
	}

	/**
	 * Parse the offer, resolve and install related instance types.
	 */
	private void parseOffer(final UpdateContext context, final String offerId, final AzureVmOffer offer) {
		if (!"compute".equals(offer.getOfferType()) || StringUtils.isEmpty(offer.getSeries())) {
			// Ignore non compute price dimension
			return;
		}

		// Detect the offer's type
		final var parts = offerId.split("-");

		// Resolve the related instance type
		final var offerTrim = StringUtils.remove(offerId, "-lowpriority");
		var id = parts[1];
		if (CharUtils.isAsciiNumeric(parts[2].charAt(0))) {
			id += "-" + parts[2];
			if (parts[3].charAt(0) == 'v') {
				id += "-" + parts[3];
			}
		}
		offer.setType(installInstanceType(context, id, toSizeName(context, id), offerTrim.endsWith("-basic"), offer));
	}

	/**
	 * Filter the managed subscription combinations.
	 */
	private boolean managedTerm(final String term) {
		return !term.contains("subscription");
	}

	/**
	 * Install the SKU and related prices associated to each term.
	 */
	private void installSku(final UpdateContext context, final ComputePrices prices, final String sku,
			final Map<String, List<String>> termMappings) {
		final var skuParts = sku.split("-");

		// Resolve the related software from the most to the least specific match
		final var software = prices.getSoftwareById().entrySet().stream().filter(e -> sku.startsWith(e.getKey()))
				.findFirst().map(Entry::getValue).map(StringUtils::upperCase).orElse(null);
		final var os = ObjectUtils.defaultIfNull(getOs(skuParts), VmOs.WINDOWS);
		if (isEnabledOs(context, os)) {
			termMappings.entrySet().stream().filter(e -> managedTerm(e.getKey()))
					.forEach(e -> installTermPrices(context, prices, sku, os, software,
							installPriceTerm(context, prices, e.getKey(), sku), e.getKey(), e.getValue()));
		}
	}

	private void installTermPrices(final UpdateContext context, final ComputePrices prices, final String sku,
			final VmOs os, final String software, final ProvInstancePriceTerm term, final String termName,
			final List<String> components) {
		final var code = term.getCode() + "/" + sku;
		final var byol = termName.contains("ahb");
		checkComponents(context, prices, components, sku, termName, this::isEnabledType, (type, edition, storageEngine,
				cost, r) -> installInstancePrice(context, term, os, code, type, cost, software, byol, r));
	}

	private VmOs getOs(final String[] parts) {
		return Arrays.stream(parts).map(this::getOs).filter(Objects::nonNull).findFirst().orElse(null);
	}

	private VmOs getOs(final String osName) {
		return EnumUtils.getEnum(VmOs.class, osName.replace("redhat", "RHEL").replace("sles", "SUSE").toUpperCase());
	}

	/**
	 * Install a new instance price as needed.
	 */
	private void installInstancePrice(final UpdateContext context, final ProvInstancePriceTerm term, final VmOs os,
			final String localCode, final ProvInstanceType type, final double monthlyCost, final String software,
			final boolean byol, final String region) {
		var price = context.getPrevious().computeIfAbsent(region + (byol ? "/byol/" : "/") + localCode, code -> {
			// New instance price (not update mode)
			final var newPrice = new ProvInstancePrice();
			newPrice.setCode(code);
			return newPrice;
		});
		copyAsNeeded(context, price, p -> {
			p.setLocation(installRegion(context, region, null));
			p.setOs(os);
			p.setSoftware(software);
			p.setLicense(byol ? ProvInstancePrice.LICENSE_BYOL : null);
			p.setTerm(term);
			p.setTenancy(dedicatedTypes.contains(type.getCode()) ? ProvTenancy.DEDICATED : ProvTenancy.SHARED);
			p.setType(type);
			p.setPeriod(term.getPeriod());
		});

		// Update the cost
		saveAsNeeded(context, price, round3Decimals(monthlyCost), ipRepository);
	}

	/**
	 * Install a new instance type as needed.
	 */
	private ProvInstanceType installInstanceType(final UpdateContext context, final String code, final String name,
			final boolean isBasic, final AzureVmOffer azType) {
		final var type = context.getInstanceTypes().computeIfAbsent(isBasic ? code + "-b" : code.toLowerCase(), n -> {
			// New instance type (not update mode)
			final var newType = new ProvInstanceType();
			newType.setNode(context.getNode());
			newType.setCode(n);
			return newType;
		});

		// Merge as needed
		return copyAsNeeded(context, type, t -> {
			t.setName(isBasic ? name + " Basic" : name);
			t.setCpu(azType.getCores());
			t.setRam((int) (azType.getRam() * 1024d));
			t.setDescription("{\"series\":\"" + azType.getSeries() + "\",\"disk\":" + azType.getDiskSize() + "}");
			t.setConstant(azType.getSeries().charAt(0) != 'B');
			t.setAutoScale(!isBasic);

			// Rating
			final var rate = isBasic ? Rate.LOW : Rate.GOOD;
			t.setCpuRate(isBasic ? Rate.LOW : getRate("cpu", t.getCode()));
			t.setRamRate(rate);
			t.setNetworkRate(getRate("network", t.getCode()));
			t.setStorageRate(rate);
		}, itRepository);
	}

	/**
	 * Build the VM sizes where tenancy is dedicated.
	 *
	 * @see <a href="https://docs.microsoft.com/en-us/azure/virtual-machines/windows/sizes-memory">sizes-memory</a>
	 * @see <a href="https://docs.microsoft.com/en-us/azure/virtual-machines/linux/isolation">isolation</a>
	 */
	@PostConstruct
	public void initVmTenancy() {
		// Retiring D15_v2/DS15_v2 isolation on May 15, 2020
		dedicatedTypes.addAll(Arrays.asList("e64", "m128ms", "g5", "gs5", "ds15v2", "d15v2", "f72v2", "l32"));
	}

	/**
	 * Read the network rate mapping. File containing the mapping from the Azure network rate to the normalized
	 * application rating.
	 *
	 * @see <a href= "https://azure.microsoft.com/en-us/pricing/details/cloud-services/">cloud-services</a>
	 * @throws IOException When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initRate() throws IOException {
		initRate("cpu");
		initRate("network");
	}
}
