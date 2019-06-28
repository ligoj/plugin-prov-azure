/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.database;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.azure.catalog.ValueWrapper;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning database price service for Azure. Manage install or update of prices.<br>
 * Currently,only vCore model is supported, not Hyperscale, not Hybrid, not elastic, not single database.
 *
 * @see <a href="https://docs.microsoft.com/en-us/azure/sql-database/sql-database-service-tiers-dtu">Service tiers in
 *      the DTU-based purchase model</a>
 * @see <a href="https://docs.microsoft.com/en-us/azure/sql-database/sql-database-service-tiers-vcore">vCore service
 *      tiers, Azure Hybrid Benefit, and migration</a>
 *
 * @see <a href="https://docs.microsoft.com/en-us/azure/sql-database/sql-database-managed-instance">Use SQL Database
 *      advanced data security with virtual networks and near 100% compatibility</a>
 * @see <a href="https://docs.microsoft.com/en-us/azure/mysql/concepts-limits">Limitations in Azure Database for
 *      MySQL</a>
 */
@Slf4j
@Component
public class AzurePriceImportDatabase extends AbstractAzureImport {

	/**
	 * Configuration key used for enabled database type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_DTYPE = ProvAzurePluginResource.KEY + ":database-type";

	private static final String STEP_COMPUTE = "db-%s-%s";

	/**
	 * Mapping from the database term code to it's identifier.
	 */
	private Map<String, String> terms;

	/**
	 * Mapping from the database type/engine to RAM/vCore ratio.
	 */
	private Map<String, Double> ramVcore;

	/**
	 * Install or update prices.
	 *
	 * @throws IOException When prices cannot be remotely read.
	 */
	@Override
	public void install(final UpdateContext context) throws IOException {
		context.setValidDatabaseType(Pattern.compile(configuration.get(CONF_DTYPE, ".*")));
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(ProvDatabaseType::getName, Function.identity())));
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(ProvInstancePriceTerm::getCode, Function.identity())));
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(ProvStorageType::getName, Function.identity())));
		context.setStorageTypesStatic(csvForBean.toBean(ProvStorageType.class, "csv/azure-db-storage-type.csv").stream()
				.collect(Collectors.toConcurrentMap(ProvStorageType::getName, Function.identity())));
		context.setPreviousStorage(spRepository.findAllBy("type.node", context.getNode()).stream()
				.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));

		// Not SQL engine
		final String STD_PREFIX = "(generalpurpose|basic|memoryoptimized)-";
		context.setToStorage(Map.ofEntries(toEntry(STD_PREFIX + "backup-(lrs|grs)", m -> "db-backup-" + m.group(2)),
				toEntry(STD_PREFIX + "storage", m -> m.group(1).equals("basic") ? "db-standard" : "db-premium")));
		context.setToDatabase(
				Map.ofEntries(toEntry(STD_PREFIX + "compute-g(\\d+)-(\\d+)", m -> toSimpleName(m.group(1)),
						m -> Integer.valueOf(m.group(2)), m -> Integer.valueOf(m.group(3)), m -> DEFAULT_TERM)));
		installPrices(context, "mysql", "MYSQL", null, null);
		installPrices(context, "mariadb", "MARIADB", null, null);
		installPrices(context, "postgresql", "POSTGRESQL", null, null);

		// SQL Server engine only
		final String SQL_PREFIX = "managed-vcore-";
		context.setToStorage(Map.ofEntries(toEntry(SQL_PREFIX + "backup", m -> "db-backup-lrs"),
				toEntry("managed-instance-pitr-backup-storage-ra-grs", m -> "db-backup-grs"),
				toEntry(SQL_PREFIX + "general-purpose-storage", m -> "sql-gp"),
				toEntry(SQL_PREFIX + "business-critical-storage", m -> "sql-bc-4,sql-bc-5,sql-bc-5-8,sql-bc-5-24")));
		context.setToDatabase(Map.ofEntries(toEntry(
				SQL_PREFIX + "(business-critical|general-purpose)-gen(\\d+)-(\\d+)-(per-hour(-one-year-reserved)?)",
				m -> "sql-" + toSimpleName(m.group(1)), m -> Integer.valueOf(m.group(2)),
				m -> Integer.valueOf(m.group(3)), m -> terms.getOrDefault(m.group(4), DEFAULT_TERM))));
		installPrices(context, "sql-database", "SQL SERVER", "ENTERPRISE", "SQL SERVER");
	}

	private Entry<Pattern, Function<Matcher, String>> toEntry(final String pattern,
			final Function<Matcher, String> mapper) {
		return Map.entry(Pattern.compile(pattern), mapper);
	}

	private Entry<Pattern, DbConfiguration> toEntry(final String pattern, final Function<Matcher, String> tier,
			final ToIntFunction<Matcher> gen, final ToIntFunction<Matcher> vcore,
			final Function<Matcher, String> term) {
		return Map.entry(Pattern.compile(pattern), new DbConfiguration(tier, gen, vcore, term));
	}

	private String toSimpleName(final String name) {
		if ("generalpurpose".equals(name) || "general-purpose".equals(name)) {
			return "gp";
		}
		if ("business-critical".equals(name)) {
			return "bc";
		}
		if ("memoryoptimized".equals(name)) {
			return "mo";
		}
		return name;
	}

	/**
	 * Install Pay-as-you-Go, one year, three years compute prices from the JSON file provided by Azure for the given
	 * category.
	 *
	 * @param context The update context.
	 */
	private void installPrices(final UpdateContext context, final String path, final String engine,
			final String edition, final String instanceEngine) throws IOException {
		final Node node = context.getNode();
		nextStep(node, String.format(STEP_COMPUTE, engine, "initialize"));

		// Get previous prices
		context.setPreviousDatabase(
				dpRepository.findAllBy("type.node", context.getNode(), new String[] { "engine" }, engine).stream()
						.collect(Collectors.toMap(ProvDatabasePrice::getCode, Function.identity())));

		// Fetch the remote prices stream and build the prices object
		nextStep(node, String.format(STEP_COMPUTE, engine, "retrieve-catalog"));
		try (CurlProcessor curl = new CurlProcessor()) {
			final String rawJson = StringUtils.defaultString(curl.get(getDatabaseApi(path)), "{}");
			final DatabasePrices prices = objectMapper.readValue(rawJson, DatabasePrices.class);

			nextStep(node, String.format(STEP_COMPUTE, engine, "update"));
			// Install related regions
			prices.getRegions().stream().filter(r -> isEnabledRegion(context, r))
					.forEach(r -> installRegion(context, r));

			// Install prices
			prices.getOffers().entrySet().forEach(e -> {
				if (context.getToStorage().entrySet().stream().noneMatch(s -> {
					final Matcher sMatch = s.getKey().matcher(e.getKey());
					if (sMatch.matches()) {
						// Storage price
						installStoragePrices(context, s.getValue().apply(sMatch), e);
						return true;
					}
					return false;
				})) {
					context.getToDatabase().entrySet().stream().anyMatch(s -> {
						final Matcher sMatch = s.getKey().matcher(e.getKey());
						if (sMatch.matches()) {
							// Compute price
							installDbPrices(context, engine, edition, instanceEngine, sMatch, s.getValue(), e);
							return true;
						}
						return false;
					});
				}
			});
		}
	}

	/**
	 * If the storage type is enabled, iterate over regions enabling this instance type and install or update the price.
	 */
	private void installStoragePrices(final UpdateContext context, final String typeName,
			final Entry<String, AzureDatabasePrice> azEntry) {
		final AzureDatabasePrice vmPrice = azEntry.getValue();
		Arrays.stream(typeName.split(",")).map(name -> installStorageType(context, name)).filter(Objects::nonNull)
				.forEach(type -> vmPrice.getPrices().entrySet().stream()
						.filter(pl -> isEnabledRegion(context, pl.getKey()))
						.forEach(pl -> installStoragePrice(context, type, pl.getKey(), pl.getValue().getValue())));
	}

	/**
	 * Install or update a storage type.
	 */
	private ProvStorageType installStorageType(final UpdateContext context, final String name) {
		final ProvStorageType type = context.getStorageTypesStatic().get(name);
		final ProvStorageType newType = context.getStorageTypes().computeIfAbsent(name, n -> {
			final ProvStorageType newType2 = new ProvStorageType();
			newType2.setNode(context.getNode());
			newType2.setName(name);
			return newType2;
		});
		return context.getStorageTypesMerged().computeIfAbsent(name, t -> {
			// Copy static attributes
			newType.setDescription(type.getDescription());
			newType.setAvailability(type.getAvailability());
			newType.setEngine(type.getEngine());
			newType.setDatabaseType(type.getDatabaseType());
			newType.setInstanceType(type.getInstanceType());
			newType.setDurability9(type.getDurability9());
			newType.setIops(type.getIops());
			newType.setLatency(type.getLatency());
			newType.setMaximal(type.getMaximal());
			newType.setMinimal(type.getMinimal());
			newType.setOptimized(type.getOptimized());
			newType.setThroughput(type.getThroughput());
			stRepository.saveAndFlush(newType);
			return newType;
		});
	}

	/**
	 * Install or update a storage price.
	 *
	 * @param context The update context.
	 */
	private void installStoragePrice(final UpdateContext context, final ProvStorageType type, final String region,
			final double cost) {
		final ProvStoragePrice price = context.getPreviousStorage().computeIfAbsent(region + "/" + type, code -> {
			final ProvStoragePrice newPrice = new ProvStoragePrice();
			newPrice.setType(type);
			newPrice.setLocation(context.getRegions().get(region));
			newPrice.setCode(code);
			return newPrice;
		});
		saveAsNeeded(price, cost, spRepository::saveAndFlush);
	}

	private void installDbPrices(final UpdateContext context, final String engine, final String edition,
			final String storageEngine, final Matcher matcher, final DbConfiguration conf,
			final Entry<String, AzureDatabasePrice> azEntry) {
		final String tier = conf.getToTier().apply(matcher); // basic, sql-gp, sql-bc, mo, gp
		final int gen = conf.getToGen().applyAsInt(matcher); // (Gen)4, (Gen)5,...
		final int vcore = conf.getToVcore().applyAsInt(matcher); // 1, 2, 4, 32,...
		Optional.of(tier + "-gen" + gen + "-" + vcore).filter(t -> isEnabledDatabase(context, t))
				.map(t -> installDbType(context, t, engine, azEntry.getValue(), tier, gen, vcore)).ifPresent(type -> {
					final String localCode = engine + "/" + azEntry.getKey();
					final ProvInstancePriceTerm term = context.getPriceTerms().get(conf.getToTerm().apply(matcher));

					// Iterate over regions enabling this instance type
					azEntry.getValue().getPrices().entrySet().stream()
							.filter(pl -> isEnabledRegion(context, pl.getKey())).forEach(pl -> installDbPrice(context,
									term, localCode, type, engine, edition, pl, storageEngine));
				});
	}

	/**
	 * Install a new instance price as needed.
	 */
	private void installDbPrice(final UpdateContext context, final ProvInstancePriceTerm term, final String localCode,
			final ProvDatabaseType type, final String engine, final String edition,
			final Entry<String, ValueWrapper> entry, final String storageEngine) {
		final Map<String, ProvDatabasePrice> previous = context.getPreviousDatabase();
		final ProvDatabasePrice price = previous.computeIfAbsent(entry.getKey() + "/" + localCode, code -> {
			// New instance price (not update mode)
			final ProvDatabasePrice newPrice = new ProvDatabasePrice();
			newPrice.setCode(code);
			newPrice.setLocation(installRegion(context, entry.getKey(), null));
			newPrice.setEngine(engine);
			newPrice.setStorageEngine(storageEngine);
			newPrice.setEdition(edition);
			newPrice.setTerm(term);
			newPrice.setType(type);
			return newPrice;
		});

		// Update the cost
		saveAsNeeded(price, price.getCost(), round3Decimals(entry.getValue().getValue() * context.getHoursMonth()),
				c -> {
					price.setCost(c);
					price.setCostPeriod(round3Decimals(c * term.getPeriod()));
				}, dpRepository::saveAndFlush);
	}

	/**
	 * Install a new database type as needed.
	 */
	private ProvDatabaseType installDbType(final UpdateContext context, final String name, final String engine,
			final AzureDatabasePrice azType, final String tier, final int gen, final int vcore) {
		final Double ram = ramVcore.getOrDefault(engine + "-gen" + gen, ramVcore.get(tier));
		if (ram == null) {
			// Not handled vCore/RAM, see Azure limits
			log.error("Unable to match database type {}/gen{}/tier= for vCore/RAM mapping", engine, gen, tier);
			return null;
		}

		final ProvDatabaseType type = context.getDatabaseTypes().computeIfAbsent(name, n -> {
			final ProvDatabaseType newType = new ProvDatabaseType();
			newType.setNode(context.getNode());
			newType.setName(n);
			return newType;
		});

		// Merge as needed
		if (context.getInstanceTypesMerged().add(name)) {
			type.setCpu((double) vcore);
			type.setRam((int) (ram.doubleValue() * 1024d));
			type.setDescription("{\"gen\":\"" + gen + "\",\"engine\":" + engine + ",\"tier\":\"" + tier + "\"}");
			type.setConstant(true);

			// Rating
			type.setCpuRate(getRate("cpu", tier));
			type.setRamRate(getRate("ram", tier));
			type.setNetworkRate(getRate("cpu", tier)); // shared with CPU
			type.setStorageRate(getRate("cpu", tier)); // shared with CPU
			dtRepository.saveAndFlush(type);
		}

		return type;
	}

	// https://docs.microsoft.com/en-us/azure/sql-database/sql-database-service-tiers-vcore
	// https://docs.microsoft.com/en-us/azure/mariadb/concepts-pricing-tiers
	// https://docs.microsoft.com/en-us/azure/mysql/concepts-pricing-tiers
	// https://docs.microsoft.com/en-us/azure/postgresql/concepts-pricing-tiers
	// Rates RAM: Basic/2, General Purpose/5, Memory Optimized/10

	// https://azure.microsoft.com/api/v2/pricing/redis-cache/calculator/
	// https://azure.microsoft.com/api/v2/pricing/cosmos-db/calculator/

	// ignore keys: '-hybrid-benefit', '-dtu-', '-hyperscale-', 'elastic-',
	// includes only: "managed-vcore-"
	private String getDatabaseApi(final String engine) {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES) + "/" + engine + "/calculator/";
	}

	/**
	 * Build database term mapping
	 * 
	 * @throws IOException When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initDatabaseTerm() throws IOException {
		terms = toMap("azure-database-term.json", MAP_STR);
	}

	/**
	 * Build database RAM mapping
	 * 
	 * @throws IOException When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initDatabaseRam() throws IOException {
		ramVcore = toMap("azure-database-ram.json", MAP_DOUBLE);
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
		initRate("ram");
	}
}
