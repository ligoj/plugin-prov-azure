/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.database;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.azure.catalog.ValueWrapper;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
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
	/**
	 * Configuration key used for enabled database engine pattern names. When value is <code>null</code>, no
	 * restriction.
	 */
	public static final String CONF_ETYPE = ProvAzurePluginResource.KEY + ":database-engine";

	private static final String STEP_COMPUTE = "db-%s-%s";

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
		context.setValidDatabaseEngine(Pattern.compile(configuration.get(CONF_ETYPE, ".*")));
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(AbstractCodedEntity::getCode, Function.identity())));
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(ProvInstancePriceTerm::getCode, Function.identity())));
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(AbstractCodedEntity::getCode, Function.identity())));
		context.setStorageTypesStatic(csvForBean.toBean(ProvStorageType.class, "csv/azure-db-storage-type.csv").stream()
				.collect(Collectors.toConcurrentMap(AbstractCodedEntity::getCode, Function.identity())));
		context.setPreviousStorage(spRepository.findAllBy("type.node", context.getNode()).stream()
				.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));

		// Not SQL engine
		final String STD_PREFIX = "(generalpurpose|basic|memoryoptimized)-";
		context.setToStorage(Map.ofEntries(toEntry(STD_PREFIX + "backup-(lrs|grs)", m -> "db-backup-" + m.group(2)),
				toEntry(STD_PREFIX + "storage", m -> m.group(1).equals("basic") ? "db-standard" : "db-premium")));
		context.setToDatabase(Map.ofEntries(toEntry(STD_PREFIX + "compute-g(\\d+)-(\\d+)",
				m -> toSimpleName(m.group(1)), m -> Integer.valueOf(m.group(2)), m -> Integer.valueOf(m.group(3)))));
		installPrices(context, "mysql", "MYSQL", null, null);
		installPrices(context, "mariadb", "MARIADB", null, null);
		installPrices(context, "postgresql", "POSTGRESQL", null, null);

		// SQL Server engine only
		final String SQL_PREFIX = "managed-vcore-";
		context.setToStorage(Map.ofEntries(toEntry(SQL_PREFIX + "backup", m -> "db-backup-lrs"),
				toEntry("managed-instance-pitr-backup-storage-ra-grs", m -> "db-backup-grs"),
				toEntry(SQL_PREFIX + "general-purpose-storage", m -> "sql-gp"),
				toEntry(SQL_PREFIX + "business-critical-storage", m -> "sql-bc-4,sql-bc-5,sql-bc-5-8,sql-bc-5-24")));
		context.setToDatabase(
				Map.ofEntries(toEntry(SQL_PREFIX + "(business-critical|general-purpose)-gen(\\d+)-(\\d+)(-.*)?",
						m -> "sql-" + toSimpleName(m.group(1)), m -> Integer.valueOf(m.group(2)),
						m -> Integer.valueOf(m.group(3)))));
		installPrices(context, "sql-database", "SQL SERVER", "ENTERPRISE", "SQL SERVER");
	}

	private Entry<Pattern, Function<Matcher, String>> toEntry(final String pattern,
			final Function<Matcher, String> mapper) {
		return Map.entry(Pattern.compile(pattern), mapper);
	}

	private Entry<Pattern, DbConfiguration> toEntry(final String pattern, final Function<Matcher, String> tier,
			final ToIntFunction<Matcher> gen, final ToIntFunction<Matcher> vcore) {
		return Map.entry(Pattern.compile(pattern), new DbConfiguration(tier, gen, vcore));
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
	 * Install the SKU and related prices associated to each term.
	 */
	protected void installSku(final UpdateContext context, final DatabasePrices prices, final String sku,
			final Map<String, List<String>> terms, final String engine) {
		terms.forEach((term, components) -> installTermPrices(context, prices, sku,
				installPriceTerm(context, prices, term, sku), term, engine, components));
	}

	/**
	 * Install Pay-as-you-Go, one year, three years database prices from the JSON file provided by Azure for the given
	 * engine.
	 */
	private void installPrices(final UpdateContext context, final String path, final String engine,
			final String edition, final String storageEngine) throws IOException {
		final Node node = context.getNode();
		if (!isEnabledEngine(context, engine)) {
			// This engine is disabled
			nextStep(node, String.format(STEP_COMPUTE, engine, "disabled"));
			return;
		}

		nextStep(node, String.format(STEP_COMPUTE, engine, "initialize"));
		// Get previous prices
		context.setPreviousDatabase(
				dpRepository.findAllBy("type.node", context.getNode(), new String[] { "engine" }, engine).stream()
						.collect(Collectors.toMap(ProvDatabasePrice::getCode, Function.identity())));

		// Fetch the remote prices stream and build the prices object
		nextStep(node, String.format(STEP_COMPUTE, engine, "retrieve-catalog"));
		try (var curl = new CurlProcessor()) {
			final var rawJson = StringUtils.defaultString(curl.get(getDatabaseApi(path)), "{}");
			final var prices = objectMapper.readValue(rawJson, DatabasePrices.class);

			nextStep(node, String.format(STEP_COMPUTE, engine, "update"));
			commonPreparation(context, prices);
			prices.getComputeTypes().forEach(n -> prices.getSizesById().put(n.getId(), n.getName()));

			// Parse offers
			prices.getOffers().entrySet().stream().forEach(e -> {
				if (e.getValue().getPrices().containsKey("pergb")) {
					context.getToStorage().entrySet().stream().anyMatch(s -> {
						final Matcher sMatch = s.getKey().matcher(e.getKey());
						if (sMatch.matches()) {
							// Storage price
							installStoragePrices(context, s.getValue().apply(sMatch), e);
							return true;
						}
						return false;
					});
				} else {
					context.getToDatabase().entrySet().stream().anyMatch(s -> {
						final Matcher sMatch = s.getKey().matcher(e.getKey());
						if (sMatch.matches()) {
							// Compute price
							parseOffer(context, prices, engine, edition, storageEngine, sMatch, s.getValue(),
									e.getKey(), e.getValue());
							return true;
						}
						return false;
					});
				}
			});

			// Install SKUs and install prices
			nextStep(node, String.format(STEP_COMPUTE, engine, "install"));
			prices.getSkus().entrySet().stream()
					.filter(e -> !e.getKey().contains("-software-") && !e.getKey().startsWith("hyperscale")
							&& !e.getKey().contains("-dtu-") && !e.getKey().startsWith("elastic"))
					.forEach(e -> installSku(context, prices, e.getKey(), e.getValue(), engine));
		}
	}

	private void installTermPrices(final UpdateContext context, final DatabasePrices prices, final String sku,
			final ProvInstancePriceTerm term, final String termName, final String engine,
			final List<String> components) {
		ProvDatabaseType type = null;
		final List<Map<String, ValueWrapper>> localCosts = new ArrayList<>();
		String edition = null;
		String storageEngine = null;
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
				log.error("Invalid tiers reference  {} found for SKU {} in term {}", tiers, sku, termName);
				return;
			}
			localCosts.add(localPrices);
			type = ObjectUtils.defaultIfNull(offer.getType(), type);
			edition = ObjectUtils.defaultIfNull(offer.getEdition(), edition);
			storageEngine = ObjectUtils.defaultIfNull(offer.getStorageEngine(), storageEngine);
		}
		if (type == null) {
			// Any invalid part invalidate the list
			log.error("Unresolved type found for SKU {} in term {}", sku, termName);
			return;
		}

		if (!isEnabledType(context, type.getCode())) {
			// Ignored type
			return;
		}

		// Install the local prices with global cost
		final var typeF = type;
		final var storageEngineF = storageEngine;
		final var editionF = edition;
		final var regions = localCosts.stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toSet());
		final var localCode = term.getCode() + "/" + sku + "/" + engine;
		final var byol = termName.contains("ahb");

		// Iterate over regions enabling this instance type
		regions.stream().filter(r -> isEnabledRegion(context, r))
				.forEach(r -> installDbPrice(context, term, localCode, typeF,
						localCosts.stream().mapToDouble(lc -> lc.get(r).getValue()).sum() * context.getHoursMonth(),
						engine, editionF, storageEngineF, byol, r));
	}

	/**
	 * Install a new instance price as needed.
	 */
	private void installDbPrice(final UpdateContext context, final ProvInstancePriceTerm term, final String localCode,
			final ProvDatabaseType type, final double monthlyCost, final String engine, final String edition,
			final String storageEngine, final boolean byol, final String region) {
		final Map<String, ProvDatabasePrice> previous = context.getPreviousDatabase();
		final ProvDatabasePrice price = previous.computeIfAbsent(region + (byol ? "/byol/" : "/") + localCode, code -> {
			// New instance price
			final ProvDatabasePrice newPrice = new ProvDatabasePrice();
			newPrice.setCode(code);
			return newPrice;
		});

		copyAsNeeded(context, price, p -> {
			p.setLocation(installRegion(context, region, null));
			p.setEngine(engine);
			p.setStorageEngine(storageEngine);
			p.setEdition(edition);
			p.setTerm(term);
			p.setType(type);
			p.setLicense(byol ? ProvInstancePrice.LICENSE_BYOL : null);
			p.setPeriod(term.getPeriod());
		});

		// Update the cost
		saveAsNeeded(context, price, round3Decimals(monthlyCost), p -> {
			p.setCostPeriod(round3Decimals(monthlyCost * Math.max(1, term.getPeriod())));
			dpRepository.save(p);
		});
	}

	private void parseOffer(final UpdateContext context, final DatabasePrices prices, final String engine,
			final String edition, final String storageEngine, final Matcher matcher, final DbConfiguration conf,
			final String offerId, final AzureDatabaseOffer offer) {
		final String tier = conf.getToTier().apply(matcher); // basic, sql-gp, sql-bc, mo, gp
		final int gen = conf.getToGen().applyAsInt(matcher); // (Gen)4, (Gen)5,...
		final int vcore = conf.getToVcore().applyAsInt(matcher); // 1, 2, 4, 32,...
		Optional.of(tier + "-gen" + gen + "-" + vcore).filter(t -> isEnabledDatabase(context, t)).ifPresent(t -> {
			offer.setType(installDbType(context, t, t, engine, offer, tier, gen, vcore));
			offer.setEdition(edition);
			offer.setStorageEngine(storageEngine);
		});
	}

	/**
	 * If the storage type is enabled, iterate over regions enabling this instance type and install or update the price.
	 */
	private void installStoragePrices(final UpdateContext context, final String typeCodes,
			final Entry<String, AzureDatabaseOffer> azEntry) {
		final AzureDatabaseOffer offer = azEntry.getValue();
		Arrays.stream(typeCodes.split(",")).map(code -> installStorageType(context, code)).filter(Objects::nonNull)
				.forEach(type -> offer.getPrices().get("pergb").entrySet().stream()
						.filter(pl -> isEnabledRegion(context, pl.getKey()))
						.forEach(pl -> installStoragePrice(context, type, pl.getKey(), pl.getValue().getValue())));
	}

	/**
	 * Install or update a storage type.
	 */
	private ProvStorageType installStorageType(final UpdateContext context, final String code) {
		final ProvStorageType type = context.getStorageTypesStatic().get(code);
		final ProvStorageType newType = context.getStorageTypes().computeIfAbsent(code, n -> {
			final ProvStorageType newType2 = new ProvStorageType();
			newType2.setNode(context.getNode());
			newType2.setCode(code);
			return newType2;
		});
		return context.getStorageTypesMerged().computeIfAbsent(code, t -> {
			// Copy static attributes
			newType.setName(t);
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
			newPrice.setCode(code);
			return newPrice;
		});
		copyAsNeeded(context, price, p -> {
			p.setType(type);
			p.setLocation(context.getRegions().get(region));
		});
		saveAsNeeded(context, price, cost, spRepository::saveAndFlush);
	}

	/**
	 * Install a new database type as needed.
	 */
	private ProvDatabaseType installDbType(final UpdateContext context, final String code, final String name,
			final String engine, final AzureDatabaseOffer azType, final String tier, final int gen, final int vcore) {
		final Double ram = ramVcore.getOrDefault(engine + "-gen" + gen, ramVcore.get(tier));
		if (ram == null) {
			// Not handled vCore/RAM, see Azure limits
			log.error("Unable to match database type {}/gen{}/tier= for vCore/RAM mapping", engine, gen, tier);
			return null;
		}

		final ProvDatabaseType type = context.getDatabaseTypes().computeIfAbsent(code.toLowerCase(), n -> {
			final ProvDatabaseType newType = new ProvDatabaseType();
			newType.setNode(context.getNode());
			newType.setCode(n);
			return newType;
		});

		// Merge as needed
		if (context.getInstanceTypesMerged().add(type.getCode())) {
			type.setCpu((double) vcore);
			type.setRam((int) (ram.doubleValue() * 1024d));
			type.setName(name);
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

	// ignore keys: '-dtu-', '-hyperscale-', 'elastic-',
	// includes only: "managed-vcore-"
	private String getDatabaseApi(final String engine) {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES_V3) + "/" + engine + "/calculator/";
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
