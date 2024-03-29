/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.disk;

import lombok.extern.slf4j.Slf4j;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.model.*;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The provisioning storage price service for Azure. Manage install or update of prices.<br>
 * TODO "one-year" and "three-year" terms are not managed
 *
 * @see <a href="https://azure.microsoft.com/api/v2/pricing/storage/calculator/">blob storage,
 *      blockBlobStorageRegions.graduatedOffers.general-purpose-v2-block-blob-hot-lrs</a>
 */
@Slf4j
@Component
public class AzurePriceImportDisk extends AbstractAzureImport {

	private String getManagedDiskApi() {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES_V2) + "/managed-disks/calculator/";
	}

	@Override
	public void install(final UpdateContext context) throws IOException {
		final var node = context.getNode();
		log.info("Azure managed-disk prices...");
		nextStep(context, "disk-initialize");

		// The previously installed location cache. Key is the location Azure name
		context.setRegions(locationRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
		initRate("storage");

		// The previously installed storage types cache. Key is the storage type name
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));
		context.setPreviousStorages(new HashMap<>());
		spRepository.findAllBy("type.node.id", node.getId()).forEach(p -> context.getPreviousStorages()
				.computeIfAbsent(p.getType(), t -> new HashMap<>()).put(p.getLocation(), p));

		// Fetch the remote prices stream
		nextStep(context, "disk-retrieve-catalog");
		try (var curl = new CurlProcessor()) {
			final var rawJson = Objects.toString(curl.get(getManagedDiskApi()), "{}");
			final var prices = objectMapper.readValue(rawJson, ManagedDisks.class);

			// Install related regions
			nextStep(context, "disk-update-catalog");
			commonPreparation(context, prices);
			prices.getSizes().forEach(n -> context.getSizesById().put(n.getId(), n.getName()));

			final var offers = prices.getOffers();
			// Get transaction costs
			context.setTransactionsHdd(offers.getOrDefault("transactions-hdd", new ManagedDisk()).getPrices());
			context.setTransactionsSsd(offers.getOrDefault("transactions-ssd", new ManagedDisk()).getPrices());

			// Update or install storage price
			offers.entrySet().stream()
					.filter(p -> !p.getKey().startsWith("transactions-") && !p.getKey().endsWith("-year")
							&& !p.getKey().endsWith("disk-mount") && !p.getKey().startsWith("ultrassd"))
					.forEach(o -> installStoragePrice(context, prices, o));

			// Purge
			final var newPrices = context.getPreviousStorages().values().stream().flatMap(sp -> sp.values().stream())
					.collect(Collectors.toMap(AbstractPrice::getCode, p -> p));
			purgePrices(context, newPrices, spRepository, qsRepository);
		}
	}

	/**
	 * Install a {@link ProvStoragePrice} from an {@link ManagedDisk} offer.
	 *
	 * @param context The update context.
	 * @param offer   The current offer to install.
	 */
	private void installStoragePrice(final UpdateContext context, final ManagedDisks prices,
			final Entry<String, ManagedDisk> offer) {
		final var disk = offer.getValue();
		final var type = installStorageType(context, prices, offer.getKey(), disk);
		final var previousT = context.getPreviousStorages().computeIfAbsent(type, t -> new HashMap<>());
		disk.getPrices().entrySet().stream().filter(p -> isEnabledRegion(context, p.getKey()))
				.forEach(p -> installStoragePrice(context, previousT, context.getRegions().get(p.getKey()), type,
						p.getValue().getValue(), offer.getKey()));
	}

	/**
	 * Install or update a storage price.
	 *
	 * @param context The update context.
	 * @see <a href="https://azure.microsoft.com/en-us/pricing/details/managed-disks/"></a>
	 */
	private ProvStoragePrice installStoragePrice(final UpdateContext context,
			final Map<ProvLocation, ProvStoragePrice> regionPrices, final ProvLocation region,
			final ProvStorageType type, final double value, final String typeCode) {
		final var price = regionPrices.computeIfAbsent(region, r -> {
			final var newPrice = new ProvStoragePrice();
			newPrice.setType(type);
			newPrice.setLocation(region);
			newPrice.setCode(region.getName() + "/az/" + type.getCode());
			return newPrice;
		});

		if (type.getName().contains("snapshot")) {
			price.setCostGb(value);
		} else {
			// Fixed cost
			price.setCost(value);
		}

		if (typeCode.startsWith("standardhdd")) {
			// Additional transaction based cost : $/10,000 transaction -> $/1,000,000 transaction
			price.setCostTransaction(Optional.ofNullable(context.getTransactionsHdd().get(region.getName()))
					.map(v -> round3Decimals(v.getValue() * 100)).orElse(0d));
		} else if (typeCode.startsWith("standardssd")) {
			// Additional transaction based cost : $/10,000 transaction -> $/1,000,000 transaction
			price.setCostTransaction(Optional.ofNullable(context.getTransactionsSsd().get(region.getName()))
					.map(v -> round3Decimals(v.getValue() * 100)).orElse(0d));
		}
		context.getPrices().add(price.getCode());
		spRepository.save(price);
		return price;
	}

	/**
	 * Install or update a storage type.
	 */
	private ProvStorageType installStorageType(final UpdateContext context, final ManagedDisks prices, String code,
			final ManagedDisk disk) {
		final var isSnapshot = code.contains("snapshot");
		final var type = context.getStorageTypes().computeIfAbsent(code.toLowerCase(), n -> {
			final var newType = new ProvStorageType();
			newType.setNode(context.getNode());
			newType.setCode(n);
			return newType;
		});

		// Merge storage type statistics
		return copyAsNeeded(context, type, t -> {
			if (isSnapshot) {
				t.setName(code);
				t.setLatency(Rate.WORST);
				t.setMinimal(0);
				t.setOptimized(ProvStorageOptimized.DURABILITY);
				t.setIops(0);
				t.setThroughput(0);
			} else {
				// Complete data
				// https://docs.microsoft.com/en-us/azure/virtual-machines/windows/disk-scalability-targets
				final var parts = code.split("-");
				final var tier = prices.getTiersById().getOrDefault(parts[0], parts[0]);
				final var size = context.getSizesById().getOrDefault(parts[1], parts[1]);
				final var isPremium = code.startsWith("premium");
				final var isStandard = code.startsWith("standard");
				final var isSSD = code.contains("ssd");
				t.setName(tier + " " + size + (parts.length > 2 ? " " + parts[2].toUpperCase() : ""));
				t.setLatency(toLatency(isPremium, isSSD));
				t.setMinimal(disk.getSize());
				t.setMaximal((double) disk.getSize());
				t.setOptimized(isSSD ? ProvStorageOptimized.IOPS : null);
				if (isStandard) {
					t.setIops(Math.max(disk.getIops(), 500));
					t.setThroughput(Math.max(disk.getThroughput(), 60));
				} else {
					t.setIops(disk.getIops());
					t.setThroughput(disk.getThroughput());
				}

				// https://docs.microsoft.com/en-us/azure/storage/common/storage-redundancy
				if (code.endsWith("-zrs")) {
					t.setDurability9(12);
				} else {
					t.setDurability9(11);
				}
				t.setInstanceType(isPremium ? "%_s%" : "%");
			}
			log.info("#Save storage type name={}, code={}, isSnapshot={}", t.getName(), t.getCode(), isSnapshot);
		}, stRepository);
	}

	/**
	 * Return the latency rate from the tiers
	 */
	private Rate toLatency(final boolean isPremium, final boolean isSSD) {
		if (isPremium) {
			return Rate.BEST;
		}
		return isSSD ? Rate.GOOD : Rate.MEDIUM;
	}
}
