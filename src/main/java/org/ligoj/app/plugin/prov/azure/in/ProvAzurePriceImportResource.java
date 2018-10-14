/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.in;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.<br>
 * TODO Basic tiers does not support Load Balancing/Auto Scale<br>
 * TODO Add blob storage<br>
 * TODO Add region filter<br>
 */
@Slf4j
@Service
public class ProvAzurePriceImportResource extends AbstractImportCatalogResource {

	private static final String TERM_LOW = "lowpriority";

	private static final String STEP_COMPUTE = "compute-%s-%s";

	private static final String BY_NODE = "node.id";

	private static final TypeReference<Map<String, ProvLocation>> MAP_LOCATION = new TypeReference<>() {
		// Nothing to extend
	};

	/**
	 * Configuration key used for Azure URL prices.
	 */
	public static final String CONF_API_PRICES = ProvAzurePluginResource.KEY + ":prices-url";
	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_REGIONS = ProvAzurePluginResource.KEY + ":regions";

	private static final String DEFAULT_API_PRICES = "https://azure.microsoft.com/api/v2/pricing";
	/**
	 * Mapping from API region identifier to region name.
	 */
	private Map<String, ProvLocation> mapRegionToName = new HashMap<>();

	private Set<String> dedicatedTypes = new HashSet<>();

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	private boolean isEnabledRegion(final String region) {
		return region.matches(StringUtils.defaultIfBlank(configuration.get(CONF_REGIONS), ".*"));
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	private boolean isEnabledRegion(final NamedResource region) {
		return isEnabledRegion(region.getId());
	}

	/**
	 * Install or update prices.
	 *
	 * @throws IOException
	 *             When prices cannot be remotely read.
	 */
	public void install() throws IOException {
		final UpdateContext context = new UpdateContext();
		// Node is already persisted, install VM prices
		final Node node = nodeRepository.findOneExpected(ProvAzurePluginResource.KEY);
		context.setNode(node);
		nextStep(node, "initialize", 1);

		// The previously installed location cache. Key is the location AWS name
		context.setRegions(locationRepository.findAllBy(BY_NODE, node.getId()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));

		// Proceed to the install
		installStoragePrices(context);
		installComputePrices(context);
		nextStep(node, "finalize", 0);
	}

	protected String getManagedDiskApi() {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES) + "/managed-disks/calculator/";
	}

	protected String getVmApi(final String term) {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES) + "/virtual-machines-" + term + "/calculator/";
	}

	/**
	 * Install storage prices from the JSON file provided by AWS.
	 *
	 * @param context
	 *            The update context.
	 */
	private void installStoragePrices(final UpdateContext context) throws IOException {
		final Node node = context.getNode();
		log.info("Azure managed-disk prices...");
		nextStep(node, "managed-disk-initialize", 1);

		// The previously installed storage types cache. Key is the storage type name
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, node.getId()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
		context.setPreviousStorages(new HashMap<>());
		spRepository.findAllBy("type.node.id", node.getId()).forEach(p -> context.getPreviousStorages()
				.computeIfAbsent(p.getType(), t -> new HashMap<>()).put(p.getLocation(), p));

		// Fetch the remote prices stream
		nextStep(node, "managed-disk-retrieve-catalog", 1);
		try (CurlProcessor curl = new CurlProcessor()) {
			final String rawJson = StringUtils.defaultString(curl.get(getManagedDiskApi()), "{}");
			final ManagedDisks prices = objectMapper.readValue(rawJson, ManagedDisks.class);

			// Add region as needed
			nextStep(node, "managed-disk-update-catalog", 1);
			prices.getRegions().stream().filter(this::isEnabledRegion).forEach(r -> installRegion(context, r));

			// Update or install storage price
			final Map<String, ManagedDisk> offers = prices.getOffers();
			context.setTransactions(offers.getOrDefault("transactions", new ManagedDisk()).getPrices());
			offers.entrySet().stream().filter(p -> !"transactions".equals(p.getKey()))
					.forEach(o -> installStoragePrice(context, o));
		}
	}

	/**
	 * Install a {@link ProvStoragePrice} from an {@link ManagedDisk} offer.
	 *
	 * @param context
	 *            The update context.
	 * @param offer
	 *            The current offer to install.
	 */
	private void installStoragePrice(final UpdateContext context, final Entry<String, ManagedDisk> offer) {
		final ManagedDisk disk = offer.getValue();
		final ProvStorageType type = installStorageType(context, offer.getKey(), disk);
		final Map<ProvLocation, ProvStoragePrice> previousT = context.getPreviousStorages().computeIfAbsent(type,
				t -> new HashMap<>());
		disk.getPrices().entrySet().stream().filter(p -> isEnabledRegion(p.getKey()))
				.forEach(p -> installStoragePrice(context, previousT, context.getRegions().get(p.getKey()), type,
						p.getValue().getValue()));
	}

	/**
	 * Install or update a storage price.
	 *
	 * @param context
	 *            The update context.
	 * @see <a href="https://azure.microsoft.com/en-us/pricing/details/managed-disks/"></a>
	 */
	private ProvStoragePrice installStoragePrice(final UpdateContext context,
			final Map<ProvLocation, ProvStoragePrice> regionPrices, final ProvLocation region,
			final ProvStorageType type, final double value) {
		final ProvStoragePrice price = regionPrices.computeIfAbsent(region, r -> {
			final ProvStoragePrice newPrice = new ProvStoragePrice();
			newPrice.setType(type);
			newPrice.setLocation(region);
			newPrice.setCode(region.getName() + "-" + type.getName());
			return newPrice;
		});
		// Fixed cost
		price.setCost(value);

		if (!type.getName().startsWith("premium")) {
			// Additional transaction based cost : $/10,000 transaction -> $/1,000,000 transaction
			price.setCostTransaction(Optional.ofNullable(context.getTransactions().get(region.getName()))
					.map(v -> round3Decimals(v.getValue() * 100)).orElse(0d));
		}
		spRepository.saveAndFlush(price);
		return price;
	}

	/**
	 * Install or update a storage type.
	 */
	private ProvStorageType installStorageType(final UpdateContext context, String name, ManagedDisk disk) {
		final boolean isSnapshot = name.endsWith("snapshot");
		final ProvStorageType type = context.getStorageTypes()
				.computeIfAbsent(isSnapshot ? name : name.replace("standard-", "").replace("premium-", ""), n -> {
					final ProvStorageType newType = new ProvStorageType();
					newType.setNode(context.getNode());
					newType.setName(n);
					return newType;
				});

		// Merge storage type statistics
		updateStorageType(type, name, disk, isSnapshot);
		return type;
	}

	/**
	 * Update the given storage type and persist it
	 */
	private void updateStorageType(final ProvStorageType type, final String name, final ManagedDisk disk,
			final boolean isSnapshot) {
		if (isSnapshot) {
			type.setLatency(Rate.WORST);
			type.setMinimal(0);
			type.setOptimized(ProvStorageOptimized.DURABILITY);
			type.setIops(0);
			type.setThroughput(0);
		} else {
			// Complete data
			// Source :
			// https://docs.microsoft.com/en-us/azure/virtual-machines/windows/disk-scalability-targets
			final boolean isPremium = name.startsWith("premium");
			final boolean isStandard = name.startsWith("standard");
			type.setLatency(isPremium ? Rate.BEST : Rate.MEDIUM);
			type.setMinimal(disk.getSize());
			type.setMaximal(disk.getSize());
			type.setOptimized(isPremium ? ProvStorageOptimized.IOPS : null);
			type.setInstanceCompatible(true);
			type.setIops(isStandard && disk.getIops() == 0 ? 500 : disk.getIops());
			type.setThroughput(isStandard && disk.getThroughput() == 0 ? 60 : disk.getThroughput());
		}

		// Save the changes
		stRepository.saveAndFlush(type);
	}

	/**
	 * Install compute prices from the JSON file provided by Azure.
	 *
	 * @param context
	 *            The update context.
	 */
	private void installComputePrices(final UpdateContext context) throws IOException {
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, context.getNode().getId()).stream()
				.collect(Collectors.toMap(ProvInstanceType::getName, Function.identity())));

		// Install Pay-as-you-Go, one year, three years
		installComputePrices(context, "base", 1);
		installComputePrices(context, "base-one-year", 12);
		installComputePrices(context, "base-three-year", 36);
		nextStep(context.getNode(), "flush", 1);
	}

	private void installComputePrices(final UpdateContext context, final String termName, final int period)
			throws IOException {
		final Node node = context.getNode();
		nextStep(node, String.format(STEP_COMPUTE, termName, "initialize"), 1);

		// Get or create the term
		List<ProvInstancePriceTerm> terms = iptRepository.findAllBy(BY_NODE, node.getId());
		final ProvInstancePriceTerm term = terms.stream().filter(p -> p.getName().equals(termName)).findAny()
				.orElseGet(() -> {
					final ProvInstancePriceTerm newTerm = new ProvInstancePriceTerm();
					newTerm.setName(termName);
					newTerm.setNode(node);
					newTerm.setPeriod(period);
					newTerm.setCode(termName);
					iptRepository.saveAndFlush(newTerm);
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
					iptRepository.saveAndFlush(newTerm);
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
		nextStep(node, String.format(STEP_COMPUTE, termName, "retrieve-catalog"), 1);
		try (CurlProcessor curl = new CurlProcessor()) {
			final String rawJson = StringUtils.defaultString(curl.get(getVmApi(termName)), "{}");
			final ComputePrices prices = objectMapper.readValue(rawJson, ComputePrices.class);
			nextStep(node, String.format(STEP_COMPUTE, termName, "update"), 1);
			prices.getOffers().entrySet().stream().filter(e -> !e.getKey().equals("transactions"))
					.forEach(e -> installInstancesTerm(context, term, termLow, e));
		}
	}

	private void installInstancesTerm(final UpdateContext context, final ProvInstancePriceTerm term,
			final ProvInstancePriceTerm termLow, Entry<String, AzureVmPrice> azPrice) {
		final String[] parts = StringUtils.split(azPrice.getKey(), '-');
		final VmOs os = VmOs.valueOf(parts[0].replace("redhat", "RHEL").replace("sles", "SUSE").toUpperCase());
		final String tier = parts[2]; // Basic, Low Priority, Standard
		final boolean isBasic = "basic".equals(tier);
		final AzureVmPrice azType = azPrice.getValue();

		// Get the right term : "lowpriority" within "PayGo" or the current term
		final ProvInstancePriceTerm termU = tier.equals(TERM_LOW) ? termLow : term;
		final String globalCode = termU.getName() + "-" + azPrice.getKey();
		final ProvInstanceType type = installInstancePriceType(context, parts, isBasic, azType);

		// Iterate over regions enabling this instance type
		azType.getPrices().entrySet().stream().filter(pl -> isEnabledRegion(pl.getKey())).forEach(pl -> {
			final ProvInstancePrice price = installInstancePrice(context, termU, os, globalCode, type, pl.getKey());

			// Update the cost
			price.setCost(round3Decimals(pl.getValue().getValue() * 24 * 30.5));
			price.setCostPeriod(pl.getValue().getValue());
			ipRepository.save(price);
		});

	}

	private ProvInstancePrice installInstancePrice(final UpdateContext context, final ProvInstancePriceTerm term,
			final VmOs os, final String globalCode, final ProvInstanceType type, final String region) {
		final Map<String, ProvInstancePrice> previous = term.getName().equals(TERM_LOW)
				? context.getPreviousLowPriority()
				: context.getPrevious();
		return previous.computeIfAbsent(region + "-" + globalCode, code -> {
			// New instance price (not update mode)
			final ProvInstancePrice newPrice = new ProvInstancePrice();
			newPrice.setCode(code);
			newPrice.setLocation(context.getRegions().get(region));
			newPrice.setOs(os);
			newPrice.setTerm(term);
			newPrice.setTenancy(dedicatedTypes.contains(type.getName()) ? ProvTenancy.DEDICATED : ProvTenancy.SHARED);
			newPrice.setType(type);
			return newPrice;
		});
	}

	private ProvInstanceType installInstancePriceType(final UpdateContext context, final String[] parts,
			final boolean isBasic, final AzureVmPrice azType) {
		final ProvInstanceType type = context.getInstanceTypes().computeIfAbsent(parts[1], name -> {
			// New instance type (not update mode)
			final ProvInstanceType newType = new ProvInstanceType();
			newType.setNode(context.getNode());
			newType.setName(name);
			return newType;
		});

		// Merge as needed
		if (context.getInstanceTypesMerged().add(type.getName())) {
			type.setCpu((double) azType.getCores());
			type.setRam((int) azType.getRam() * 1024);
			type.setDescription("series:" + azType.getSeries() + ", disk:" + azType.getDiskSize() + "GiB");
			type.setConstant(!"B".equals(azType.getSeries()));

			// Rating
			final Rate rate = isBasic ? Rate.LOW : Rate.GOOD;
			type.setCpuRate(isBasic ? Rate.LOW : getRate("cpu", type.getName()));
			type.setRamRate(rate);
			type.setNetworkRate(getRate("network", type.getName()));
			type.setStorageRate(rate);
			itRepository.saveAndFlush(type);
		}

		return type;
	}

	/**
	 * Update the statistics
	 */
	private void nextStep(final Node node, final String phase, final int forward) {
		importCatalogResource.nextStep(node.getId(), t -> {
			importCatalogResource.updateStats(t);
			t.setWorkload(14); // (3term x 3steps) + (storage x3) + 2
			t.setDone(t.getDone() + forward);
			t.setPhase(phase);
		});
	}

	/**
	 * Install a new region.<br/>
	 * Also see CLI2 command <code>az account list-locations</code>
	 */
	private ProvLocation installRegion(final UpdateContext context, final NamedResource region) {
		final ProvLocation entity = context.getRegions().computeIfAbsent(region.getId(), r -> {
			final ProvLocation newRegion = new ProvLocation();
			newRegion.setNode(context.getNode());
			newRegion.setName(region.getId());
			return newRegion;
		});

		// Update the location details as needed
		final ProvLocation regionStats = mapRegionToName.getOrDefault(region.getId(), new ProvLocation());
		entity.setContinentM49(regionStats.getContinentM49());
		entity.setCountryM49(regionStats.getCountryM49());
		entity.setCountryA2(regionStats.getCountryA2());
		entity.setPlacement(regionStats.getPlacement());
		entity.setRegionM49(regionStats.getRegionM49());
		entity.setSubRegion(regionStats.getSubRegion());
		entity.setLatitude(regionStats.getLatitude());
		entity.setLongitude(regionStats.getLongitude());
		entity.setDescription(region.getName());
		locationRepository.saveAndFlush(entity);
		return entity;
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
	 * Read the network rate mapping. File containing the mapping from the AWS network rate to the normalized
	 * application rating.
	 *
	 * @see <a href= "https://azure.microsoft.com/en-us/pricing/details/cloud-services/">cloud-services</a>
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initRate() throws IOException {
		initRate("storage");
		initRate("cpu");
		initRate("network");
	}

	/**
	 * Round up to 3 decimals the given value.
	 */
	private double round3Decimals(final double value) {
		return Math.round(value * 1000d) / 1000d;
	}

	/**
	 *
	 * Read the region details from an external JSON file. File containing the mapping from the API region name to the
	 * details.
	 *
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initRegion() throws IOException {
		mapRegionToName.putAll(objectMapper.readValue(
				IOUtils.toString(new ClassPathResource("az-regions.json").getInputStream(), StandardCharsets.UTF_8),
				MAP_LOCATION));
	}
}
