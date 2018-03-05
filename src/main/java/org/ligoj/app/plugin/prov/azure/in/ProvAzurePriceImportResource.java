package org.ligoj.app.plugin.prov.azure.in;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.in.AbstractImportCatalogResource;
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
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.stereotype.Service;

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

	private static final String BY_NODE = "node.id";

	/**
	 * Configuration key used for Azure URL prices.
	 */
	public static final String CONF_API_PRICES = ProvAzurePluginResource.KEY + ":prices-url";
	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_REGIONS = ProvAzurePluginResource.KEY + ":regions";

	private static final String DEFAULT_API_PRICES = "https://azure.microsoft.com/api/v2/pricing";

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
	 */
	public void install() throws IOException {
		// Node is already persisted, install VM prices
		final Node node = nodeRepository.findOneExpected(ProvAzurePluginResource.KEY);
		nextStep(node, "initialize", 1);

		// The previously installed location cache. Key is the location AWS name
		final Map<String, ProvLocation> regions = locationRepository.findAllBy(BY_NODE, node.getId()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Proceed to the install
		installStoragePrices(node, regions);
		installComputePrices(node, regions);
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
	 * @param node
	 *            The related Azure {@link Node}
	 * @param regions
	 *            The previous available regions.
	 */
	private void installStoragePrices(final Node node, final Map<String, ProvLocation> regions) throws IOException {
		log.info("Azure managed-disk prices...");
		nextStep(node, "managed-disk-initialize", 1);

		// The previously installed storage types cache. Key is the storage type
		// name
		final Map<String, ProvStorageType> storages = stRepository.findAllBy(BY_NODE, node.getId()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));
		final Map<ProvStorageType, Map<ProvLocation, ProvStoragePrice>> previous = new HashMap<>();
		spRepository.findAllBy("type.node.id", node.getId()).forEach(p -> {
			previous.computeIfAbsent(p.getType(), t -> new HashMap<>()).put(p.getLocation(), p);
		});

		// Fetch the remote prices stream
		nextStep(node, "managed-disk-retrieve-catalog", 1);
		final String rawJson = StringUtils.defaultString(new CurlProcessor().get(getManagedDiskApi()), "{}");
		final ManagedDisks prices = objectMapper.readValue(rawJson, ManagedDisks.class);

		// Add region as needed
		nextStep(node, "managed-disk-update-catalog", 1);
		prices.getRegions().stream().filter(this::isEnabledRegion).forEach(r -> installRegion(regions, r, node));

		// Update or install storage price
		final Map<String, ManagedDisk> offers = prices.getOffers();
		final Map<String, Value> transactions = offers.getOrDefault("transactions", new ManagedDisk()).getPrices();
		offers.entrySet().stream().filter(p -> !"transactions".equals(p.getKey()))
				.forEach(o -> installStoragePrice(node, regions, storages, previous, o, transactions));
	}

	/**
	 * Install a {@link ProvStoragePrice} from an {@link ManagedDisk} offer.
	 * 
	 * @param node
	 *            The related node.
	 * @param regions
	 *            The available regions.
	 * @param storages
	 *            The available storage type.
	 * @param previous
	 *            The previous storage prices. Would be updated by this function.
	 * @param offer
	 *            The current offer to install.
	 * @param transactions
	 *            The transaction based cost by region. Key is the region name.
	 */
	private void installStoragePrice(final Node node, final Map<String, ProvLocation> regions,
			final Map<String, ProvStorageType> storages,
			final Map<ProvStorageType, Map<ProvLocation, ProvStoragePrice>> previous,
			final Entry<String, ManagedDisk> offer, final Map<String, Value> transactions) {
		final ManagedDisk disk = offer.getValue();
		final ProvStorageType type = installStorageType(storages, offer.getKey(), disk, node);
		final Map<ProvLocation, ProvStoragePrice> previousT = previous.computeIfAbsent(type, t -> new HashMap<>());
		disk.getPrices().entrySet().stream().filter(p -> isEnabledRegion(p.getKey()))
				.forEach(p -> installStoragePrice(previousT, regions.get(p.getKey()), type, p.getValue().getValue(),
						transactions));
	}

	/**
	 * Install or update a storage price.
	 * @see <a href="https://azure.microsoft.com/en-us/pricing/details/managed-disks/"></a>
	 */
	private ProvStoragePrice installStoragePrice(final Map<ProvLocation, ProvStoragePrice> regionPrices,
			final ProvLocation region, final ProvStorageType type, final double value,
			final Map<String, Value> transactions) {
		final ProvStoragePrice price = regionPrices.computeIfAbsent(region, r -> {
			final ProvStoragePrice newPrice = new ProvStoragePrice();
			newPrice.setType(type);
			newPrice.setLocation(region);
			return newPrice;
		});
		// Fixed cost
		price.setCost(value);

		if (!type.getName().startsWith("premium")) {
			// Additional transaction based cost : $/10,000 transaction -> $/1,000,000 transaction
			price.setCostTransaction(Optional.ofNullable(transactions.get(region.getName())).map(Value::getValue)
					.map(v -> round3Decimals(v * 100)).orElse(0d));
		}
		spRepository.saveAndFlush(price);
		return price;
	}

	/**
	 * Install or update a storage type.
	 */
	private ProvStorageType installStorageType(final Map<String, ProvStorageType> storages, String name,
			ManagedDisk disk, Node node) {
		final boolean isSnapshot = name.endsWith("snapshot");
		return storages.computeIfAbsent(isSnapshot ? name : name.replace("standard-", "").replace("premium-", ""),
				n -> {
					final ProvStorageType newType = new ProvStorageType();
					final boolean isPremium = name.startsWith("premium");
					final boolean isStandard = name.startsWith("standard");
					newType.setNode(node);
					newType.setName(n);
					newType.setInstanceCompatible(isPremium || isStandard);
					newType.setLatency(isPremium ? Rate.BEST : Rate.MEDIUM);
					newType.setMaximal(disk.getSize());
					newType.setOptimized(isPremium ? ProvStorageOptimized.IOPS : null);

					// Complete data
					// Source :
					// https://docs.microsoft.com/en-us/azure/virtual-machines/windows/disk-scalability-targets
					newType.setIops(isStandard && disk.getIops() == 0 ? 500 : disk.getIops());
					newType.setThroughput(isStandard && disk.getThroughput() == 0 ? 60 : disk.getThroughput());
					stRepository.saveAndFlush(newType);
					return newType;
				});
	}

	/**
	 * Install compute prices from the JSON file provided by Azure.
	 * 
	 * @param node
	 *            The related AWS {@link Node}
	 * @param regions
	 *            The available regions.
	 */
	private void installComputePrices(final Node node, final Map<String, ProvLocation> regions) throws IOException {
		final Map<String, ProvInstanceType> types = itRepository.findAllBy(BY_NODE, node.getId()).stream()
				.collect(Collectors.toMap(ProvInstanceType::getName, Function.identity()));

		// Install Pay-as-you-Go, one year, three years
		installComputePrices(node, regions, types, "base", 1);
		installComputePrices(node, regions, types, "base-one-year", 12);
		installComputePrices(node, regions, types, "base-three-year", 36);
		nextStep(node, "flush", 1);
	}

	private void installComputePrices(final Node node, final Map<String, ProvLocation> regions,
			final Map<String, ProvInstanceType> types, final String termName, final int period) throws IOException {
		nextStep(node, "compute-" + termName + "-initialize", 1);

		// Get or create the term
		final ProvInstancePriceTerm term = iptRepository.findAllBy(BY_NODE, node.getId()).stream()
				.filter(p -> p.getName().equals(termName)).findAny().orElseGet(() -> {
					final ProvInstancePriceTerm newTerm = new ProvInstancePriceTerm();
					newTerm.setName(termName);
					newTerm.setNode(node);
					newTerm.setPeriod(period);
					newTerm.setCode(termName);
					iptRepository.saveAndFlush(newTerm);
					return newTerm;
				});

		// Special "LOW PRIORITY" sub term of Pay As you Go
		final ProvInstancePriceTerm termLow = iptRepository.findAllBy(BY_NODE, node.getId()).stream()
				.filter(p -> p.getName().equals("lowpriority")).findAny().orElseGet(() -> {
					final ProvInstancePriceTerm newTerm = new ProvInstancePriceTerm();
					newTerm.setName("lowpriority");
					newTerm.setNode(node);
					newTerm.setEphemeral(true);
					newTerm.setPeriod(period);
					newTerm.setCode("lowpriority");
					iptRepository.saveAndFlush(newTerm);
					return newTerm;
				});

		// Get previous prices
		final Map<String, ProvInstancePrice> previous = ipRepository.findAllBy("term.id", term.getId()).stream()
				.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity()));

		// Fetch the remote prices stream and build the prices object
		nextStep(node, "compute-" + termName + "-retrieve-catalog", 1);
		final String rawJson = StringUtils.defaultString(new CurlProcessor().get(getVmApi(termName)), "{}");
		final ComputePrices prices = objectMapper.readValue(rawJson, ComputePrices.class);

		nextStep(node, "compute-" + termName + "-update", 1);
		prices.getOffers().entrySet().stream()
				.forEach(e -> installInstancesTerm(node, regions, types, term, termLow, previous, e));
	}

	private void installInstancesTerm(final Node node, final Map<String, ProvLocation> regions,
			final Map<String, ProvInstanceType> types, final ProvInstancePriceTerm term,
			final ProvInstancePriceTerm termLow, final Map<String, ProvInstancePrice> previous,
			Entry<String, AzureVmPrice> azPrice) {
		final String[] parts = StringUtils.split(azPrice.getKey(), '-');
		final VmOs os = VmOs.valueOf(parts[0].replace("redhat", "RHEL").replace("sles", "SUSE").toUpperCase());
		final String tier = parts[2]; // Basic, Low Priority, Standard
		final boolean isBasic = "basic".equals(tier);
		final AzureVmPrice azType = azPrice.getValue();

		// Get the right term : "lowpriority" within "PayGo" or the current term
		final ProvInstancePriceTerm termU = "tier".equals("lowpriority") ? termLow : term;
		final String globalCode = termU.getName() + "-" + azPrice.getKey();
		final ProvInstanceType type = installInstancePriceType(node, types, parts, isBasic, azType);

		// Iterate over regions enabling this instance type
		azType.getPrices().entrySet().stream().filter(pl -> isEnabledRegion(pl.getKey())).forEach(pl -> {
			final ProvInstancePrice price = installInstancePrice(regions, termU, previous, os, globalCode, type,
					pl.getKey());

			// Update the cost
			price.setCost(round3Decimals(pl.getValue().getValue() * 24 * 30.5));
			price.setCostPeriod(pl.getValue().getValue());
			ipRepository.save(price);
		});

	}

	private ProvInstancePrice installInstancePrice(final Map<String, ProvLocation> regions,
			final ProvInstancePriceTerm term, final Map<String, ProvInstancePrice> previous, final VmOs os,
			final String globalCode, final ProvInstanceType type, final String region) {
		final ProvInstancePrice price = previous.computeIfAbsent(region + "-" + globalCode, code -> {
			// New instance price (not update mode)
			final ProvInstancePrice newPrice = new ProvInstancePrice();
			newPrice.setCode(code);
			newPrice.setLocation(regions.get(region));
			newPrice.setOs(os);
			newPrice.setTerm(term);
			newPrice.setTenancy(dedicatedTypes.contains(type.getName()) ? ProvTenancy.DEDICATED : ProvTenancy.SHARED);
			newPrice.setType(type);
			return newPrice;
		});
		return price;
	}

	private ProvInstanceType installInstancePriceType(final Node node, final Map<String, ProvInstanceType> types,
			final String[] parts, final boolean isBasic, final AzureVmPrice azType) {
		final ProvInstanceType type = types.computeIfAbsent(parts[1], name -> {
			// New instance type (not update mode)
			final ProvInstanceType newType = new ProvInstanceType();
			newType.setNode(node);
			newType.setName(name);
			newType.setCpu((double) azType.getCores());
			newType.setRam((int) azType.getRam() * 1024);
			newType.setDescription("series:" + azType.getSeries() + ", disk:" + azType.getDiskSize() + "GiB");
			newType.setConstant(!"B".equals(azType.getSeries()));

			// Rating
			final Rate rate = isBasic ? Rate.LOW : Rate.GOOD;
			newType.setCpuRate(isBasic ? Rate.LOW : getRate("cpu", name));
			newType.setRamRate(rate);
			newType.setNetworkRate(getRate("network", name));
			newType.setStorageRate(rate);
			itRepository.saveAndFlush(newType);
			return newType;
		});
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
	 * Install a new region.
	 */
	private ProvLocation installRegion(final Map<String, ProvLocation> regions, final NamedResource region,
			final Node node) {
		ProvLocation entity = regions.computeIfAbsent(region.getId(), r -> {
			final ProvLocation newRegion = new ProvLocation();
			newRegion.setNode(node);
			newRegion.setName(region.getId());
			return newRegion;
		});
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
}
