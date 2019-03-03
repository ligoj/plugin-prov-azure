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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
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
 * TODO Basic tiers does not support Load Balancing/Auto Scale<br>
 */
@Slf4j
@Component
public class AzurePriceImportVm extends AbstractAzureImport {

	private static final String TERM_LOW = "lowpriority";

	private static final String STEP_COMPUTE = "vm-%s-%s";

	/**
	 * Configuration key used for enabled instance type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_ITYPE = ProvAzurePluginResource.KEY + ":instance-type";

	/**
	 * Configuration key used for enabled OS pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_OS = ProvAzurePluginResource.KEY + ":os";

	/**
	 * Ignored software part names.
	 */
	private static final String[] FILTER_SOFTWARE = { "advantage", "applications", "basic", "business", "linux",
			"redhat", "sles" };

	private Set<String> dedicatedTypes = new HashSet<>();

	/**
	 * Install or update prices.
	 *
	 * @throws IOException
	 *             When prices cannot be remotely read.
	 */
	@Override
	public void install(final UpdateContext context) throws IOException {
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*")));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*")));
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(ProvInstanceType::getName, Function.identity())));
		installComputePrices(context, "base", null);
		installComputePrices(context, "software", null);
		installComputePrices(context, "ahb", "BYOL");
	}

	private String getVmApi(final String term) {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES) + "/virtual-machines-" + term + "/calculator/";
	}

	/**
	 * Install Pay-as-you-Go, one year, three years compute prices from the JSON file provided by Azure for the given
	 * category.
	 *
	 * @param context
	 *            The update context.
	 * @param category
	 *            The price category.
	 */
	private void installComputePrices(final UpdateContext context, final String category, final String license)
			throws IOException {
		installComputePrices(context, category, DEFAULT_TERM, 1, license);
		installComputePrices(context, category + "-one-year", "one-year", 12, license);
		installComputePrices(context, category + "-three-year", "three-year", 36, license);
	}

	private void installComputePrices(final UpdateContext context, final String category, final String termName,
			final int period, final String license) throws IOException {
		final Node node = context.getNode();
		nextStep(node, String.format(STEP_COMPUTE, category, "initialize"));

		// Get or create the term
		List<ProvInstancePriceTerm> terms = iptRepository.findAllBy(BY_NODE, node);
		final ProvInstancePriceTerm term = terms.stream().filter(p -> p.getName().equals(termName)).findAny()
				.orElseGet(() -> {
					final ProvInstancePriceTerm newTerm = new ProvInstancePriceTerm();
					newTerm.setName(termName);
					newTerm.setNode(node);
					newTerm.setPeriod(period);
					newTerm.setCode(termName);
					iptRepository.save(newTerm);
					return newTerm;
				});

		// Special "LOW PRIORITY" sub term of Pay As you Go
		final ProvInstancePriceTerm termLow = terms.stream().filter(p -> p.getName().equals(TERM_LOW)).findAny()
				.orElseGet(() -> {
					final ProvInstancePriceTerm newTerm = new ProvInstancePriceTerm();
					newTerm.setName(TERM_LOW);
					newTerm.setNode(node);
					newTerm.setEphemeral(true);
					newTerm.setPeriod(0);
					newTerm.setCode(TERM_LOW);
					iptRepository.save(newTerm);
					return newTerm;
				});

		// Get previous prices
		context.setPrevious(ipRepository.findAllBy("term.id", term.getId()).stream()
				.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
		if (context.getPreviousLowPriority() == null) {
			context.setPreviousLowPriority(ipRepository.findAllBy("term.id", termLow.getId()).stream()
					.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
		}

		// Fetch the remote prices stream and build the prices object
		nextStep(node, String.format(STEP_COMPUTE, category, "retrieve-catalog"));
		try (CurlProcessor curl = new CurlProcessor()) {
			final String rawJson = StringUtils.defaultString(curl.get(getVmApi(category)), "{}");
			final ComputePrices prices = objectMapper.readValue(rawJson, ComputePrices.class);

			nextStep(node, String.format(STEP_COMPUTE, category, "update"));
			// Install related regions
			prices.getRegions().stream().filter(r -> isEnabledRegion(context, r))
					.forEach(r -> installRegion(context, r));

			// Install prices
			prices.getOffers().entrySet().stream().filter(e -> !e.getKey().equals("transactions"))
					.forEach(e -> installInstancePrices(context, term, termLow, e, license));
		}
	}

	private void installInstancePrices(final UpdateContext context, final ProvInstancePriceTerm term,
			final ProvInstancePriceTerm termLow, final Entry<String, AzureVmPrice> azEntry, final String license) {
		String[] parts = StringUtils.split(azEntry.getKey(), '-');
		final String software;

		// Extract the OS from the code
		final VmOs os;
		if (azEntry.getValue().getBaseOfferSlug() == null) {
			os = getOs(parts);
			software = null;
		} else {
			final String[] baseParts = StringUtils.split(azEntry.getValue().getBaseOfferSlug(), '-');
			os = Optional.ofNullable(getOs(parts)).orElseGet(() -> getOs(baseParts));

			// Extract the software from the code
			final String[] softwareParts = new String[parts.length - baseParts.length + 1];
			System.arraycopy(parts, 0, softwareParts, 0, softwareParts.length);
			software = Arrays.stream(softwareParts).filter(p -> Arrays.binarySearch(FILTER_SOFTWARE, p) < 0)
					.collect(Collectors.joining(" ")).toUpperCase();
			parts = baseParts;
		}
		if (os == null) {
			// Skip this price when OS has not been resolved
			log.error("Unable to compute the OS name from the entry {}", azEntry.getKey());
			return;
		}
		if (!isEnabledOs(context, os)) {
			// Ignored OS
			return;
		}

		final String typeName = Arrays.stream(parts).skip(1).limit(parts.length - 2).collect(Collectors.joining("-"));
		if (!isEnabledType(context, typeName)) {
			// Ignored type
			return;
		}

		// Extract the term from the code
		final String tier = parts[parts.length - 1]; // Basic, Low Priority, Standard

		// Get the right term : "lowpriority" within "PayGo" or the current term
		final ProvInstancePriceTerm termU = tier.equals(TERM_LOW) ? termLow : term;
		final String localCode = termU.getName() + "/" + azEntry.getKey();
		final boolean isBasic = "basic".equals(tier);

		// Install the type
		final AzureVmPrice vmPrice = azEntry.getValue();
		final ProvInstanceType type = installInstanceType(context, typeName, isBasic, vmPrice);

		// Iterate over regions enabling this instance type
		vmPrice.getPrices().entrySet().stream().filter(pl -> isEnabledRegion(context, pl.getKey())).forEach(pl -> {
			final ProvInstancePrice price = installInstancePrice(context, termU, os, localCode, type, software, license,
					pl.getKey());

			// Update the cost
			saveAsNeeded(price, price.getCost(), round3Decimals(pl.getValue().getValue() * context.getHoursMonth()),
					c -> {
						price.setCost(c);
						price.setCostPeriod(pl.getValue().getValue());
					}, ipRepository::save);
		});
	}

	private VmOs getOs(final String[] parts) {
		return Optional.ofNullable(getOs(parts[0])).or(() -> Optional.ofNullable(getOs(parts[1]))).orElse(null);
	}

	private VmOs getOs(final String osName) {
		return EnumUtils.getEnum(VmOs.class, osName.replace("redhat", "RHEL").replace("sles", "SUSE").toUpperCase());
	}

	/**
	 * Install a new instance price as needed.
	 */
	private ProvInstancePrice installInstancePrice(final UpdateContext context, final ProvInstancePriceTerm term,
			final VmOs os, final String localCode, final ProvInstanceType type, final String software,
			final String license, final String region) {
		final Map<String, ProvInstancePrice> previous = term.getName().equals(TERM_LOW)
				? context.getPreviousLowPriority()
				: context.getPrevious();
		return previous.computeIfAbsent(region + (license == null ? "/" : "/byol/") + localCode, code -> {
			// New instance price (not update mode)
			final ProvInstancePrice newPrice = new ProvInstancePrice();
			newPrice.setCode(code);
			newPrice.setLocation(installRegion(context, region, null));
			newPrice.setOs(os);
			newPrice.setSoftware(software);
			newPrice.setLicense(license);
			newPrice.setTerm(term);
			newPrice.setTenancy(dedicatedTypes.contains(type.getName()) ? ProvTenancy.DEDICATED : ProvTenancy.SHARED);
			newPrice.setType(type);
			return newPrice;
		});
	}

	/**
	 * Install a new instance type as needed.
	 */
	private ProvInstanceType installInstanceType(final UpdateContext context, final String name, final boolean isBasic,
			final AzureVmPrice azType) {
		final ProvInstanceType type = context.getInstanceTypes().computeIfAbsent(isBasic ? name + "-b" : name, n -> {
			// New instance type (not update mode)
			final ProvInstanceType newType = new ProvInstanceType();
			newType.setNode(context.getNode());
			newType.setName(n);
			return newType;
		});

		// Merge as needed
		if (context.getInstanceTypesMerged().add(type.getName())) {
			type.setCpu((double) azType.getCores());
			type.setRam((int) azType.getRam() * 1024);
			type.setDescription("{\"series\":\"" + azType.getSeries() + "\",\"disk\":" + azType.getDiskSize() + "}");
			type.setConstant(!"B".equals(azType.getSeries()));

			// Rating
			final Rate rate = isBasic ? Rate.LOW : Rate.GOOD;
			type.setCpuRate(isBasic ? Rate.LOW : getRate("cpu", type.getName()));
			type.setRamRate(rate);
			type.setNetworkRate(getRate("network", type.getName()));
			type.setStorageRate(rate);
			itRepository.save(type);
		}

		return type;
	}

	/**
	 * Build the VM sizes where tenancy is dedicated.
	 *
	 * @see <a href= "https://docs.microsoft.com/en-us/azure/virtual-machines/windows/sizes-memory">sizes-memory</a>
	 */
	@PostConstruct
	public void initVmTenancy() {
		dedicatedTypes.addAll(Arrays.asList("e64", "m128ms", "g5", "gs5", "ds15v2", "d15v2", "f72v2", "l32"));
	}

	/**
	 * Read the network rate mapping. File containing the mapping from the Azure network rate to the normalized
	 * application rating.
	 *
	 * @see <a href= "https://azure.microsoft.com/en-us/pricing/details/cloud-services/">cloud-services</a>
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initRate() throws IOException {
		initRate("cpu");
		initRate("network");
	}
}
