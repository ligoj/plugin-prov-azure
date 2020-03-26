/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.vm;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
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

import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.azure.catalog.ValueWrapper;
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
@Slf4j
@Component
public class AzurePriceImportVm extends AbstractAzureImport {

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
		final var node = context.getNode();
		nextStep(node, String.format(STEP_COMPUTE, "initialize"));
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*"), Pattern.CASE_INSENSITIVE));
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstanceType::getCode, Function.identity())));
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity())));
		context.setPrevious(ipRepository.findAllBy("term.node", node).stream()
				.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
		installComputePrices(context);
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
		final var node = context.getNode();

		// Fetch the remote prices stream and build the prices object
		nextStep(node, String.format(STEP_COMPUTE, "retrieve-catalog"));

		try (var curl = new CurlProcessor()) {
			final var rawJson = StringUtils.defaultString(curl.get(getVmApi()), "{}");
			final var prices = objectMapper.readValue(rawJson, ComputePrices.class);
			nextStep(node, String.format(STEP_COMPUTE, "parse-catalog"));
			commonPreparation(context, prices);
			prices.getSoftwareLicenses().forEach(n -> prices.getSoftwareById().put(n.getId(), n.getName()));
			prices.getSizesOneYear().forEach(n -> prices.getSizesById().put(n.getId(), n.getName()));
			prices.getSizesThreeYear().forEach(n -> prices.getSizesById().put(n.getId(), n.getName()));
			prices.getSizesPayGo().forEach(n -> prices.getSizesById().put(n.getId(), n.getName()));

			// Parse offers
			prices.getOffers().entrySet().stream().forEach(e -> parseOffer(context, prices, e.getKey(), e.getValue()));

			// Install SKUs and install prices
			nextStep(node, String.format(STEP_COMPUTE, "install"));
			prices.getSkus().forEach((sku, terms) -> installSku(context, prices, sku, terms));
		}
	}

	private void parseOffer(final UpdateContext context, final ComputePrices prices, final String offerId,
			final AzureVmOffer offer) {

		// Detect the offer's type
		final var parts = offerId.split("-");

		// Resolve the related OS
		offer.setOs(getOs(parts));

		// Resolve the related instance type
		if (StringUtils.isNotEmpty(offer.getSeries())) {
			final var offerTrim = StringUtils.remove(offerId, "-lowpriority");
			offer.setLowPriority(offerId.contains("-lowpriority"));
			offer.setType(installInstanceType(context, parts[1], toSizeName(prices, parts[1]),
					offerTrim.endsWith("-basic"), offer));
		}
	}

	private String toSizeName(final ComputePrices prices, final String id) {
		return StringUtils.defaultString(prices.getSizesById().get(id), id);
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
		termMappings.forEach((term, components) -> installTermPrices(context, prices, sku, os, software,
				installPriceTerm(context, prices, term, sku), term, components));
	}

	private void installTermPrices(final UpdateContext context, final ComputePrices prices, final String sku,
			final VmOs os, final String software, final ProvInstancePriceTerm term, final String termName,
			final List<String> components) {
		ProvInstanceType type = null;
		final double[] costs = new double[3];
		Map<String, ValueWrapper> localCosts = Collections.emptyMap();

		for (final String component : components) {
			final var parts = component.split("--");
			if (parts.length != 2) {
				// Any invalid part invalidate the list
				log.error("Invalid price {} found for SKU {} in term {}", component, sku, termName);
				return;
			}
			final var offerId = parts[0];
			final var offer = prices.getOffers().get(offerId);
			if (offer == null) {
				// Any invalid part invalidate the list
				log.error("Invalid offer reference {} found for SKU {} in term {}", offerId, sku, termName);
				return;
			}

			final var tiers = parts[1];
			final var localPrices = offer.getPrices().get(tiers);
			if (localPrices == null) {
				// Any invalid part invalidate the list
				log.error("Invalid tiers reference {} found for SKU {} in term {}", tiers, sku, termName);
				return;
			}
			if (localPrices.containsKey("global")) {
				updateCostCounters(costs, tiers, sku, localPrices.get("global").getValue());
			} else {
				localCosts = localPrices;
				type = offer.getType();
			}
		}
		if (type == null) {
			// Any invalid part invalidate the list
			log.error("Unresolved type found for SKU {} in term {}", sku, termName);
			return;
		}

		if (!isEnabledOs(context, os) || !isEnabledType(context, type.getCode())) {
			// Ignored type
			return;
		}

		// Install the local prices with global cost
		final var typeF = type;
		final var osF = os;
		final var perMonth = costs[PER_MONTH]
				+ (costs[PER_HOUR] + costs[PER_CORE] * typeF.getCpu()) * context.getHoursMonth();
		final var code = term.getCode() + "/" + sku;
		final var byol = termName.contains("ahb");
		localCosts.entrySet().stream().filter(e -> isEnabledRegion(context, e.getKey()))
				.forEach(e -> installInstancePrice(context, term, osF, code, typeF,
						e.getValue().getValue() * context.getHoursMonth() + perMonth, software, byol, e.getKey()));
	}

	private VmOs getOs(final String[] parts) {
		return Arrays.stream(parts).map(p -> getOs(p)).filter(Objects::nonNull).findFirst().orElse(null);
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
		if (context.getInstanceTypesMerged().add(type.getCode())) {
			type.setName(isBasic ? name + " Basic" : name);
			type.setCpu((double) azType.getCores());
			type.setRam((int) azType.getRam() * 1024);
			type.setDescription("{\"series\":\"" + azType.getSeries() + "\",\"disk\":" + azType.getDiskSize() + "}");
			type.setConstant(!"B".equals(azType.getSeries()));
			type.setAutoScale(!isBasic);

			// Rating
			final var rate = isBasic ? Rate.LOW : Rate.GOOD;
			type.setCpuRate(isBasic ? Rate.LOW : getRate("cpu", type.getCode()));
			type.setRamRate(rate);
			type.setNetworkRate(getRate("network", type.getCode()));
			type.setStorageRate(rate);
			itRepository.save(type);
		}

		return type;
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
