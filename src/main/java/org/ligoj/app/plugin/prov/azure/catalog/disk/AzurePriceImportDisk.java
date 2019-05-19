/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.disk;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning storage price service for Azure. Manage install or update of prices.<br>
 *
 * @see <a heref="https://azure.microsoft.com/api/v2/pricing/storage/calculator/">blob storage,
 *      blockBlobStorageRegions.graduatedOffers.general-purpose-v2-block-blob-hot-lrs</a>
 */
@Slf4j
@Component
public class AzurePriceImportDisk extends AbstractAzureImport {

	private String getManagedDiskApi() {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES) + "/managed-disks/calculator/";
	}

	@Override
	public void install(final UpdateContext context) throws IOException {
		final Node node = context.getNode();
		log.info("Azure managed-disk prices...");
		nextStep(context, "disk-initialize");

		// The previously installed location cache. Key is the location Azure name
		context.setRegions(locationRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
		initRate("storage");

		// The previously installed storage types cache. Key is the storage type name
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
		context.setPreviousStorages(new HashMap<>());
		spRepository.findAllBy("type.node.id", node.getId()).forEach(p -> context.getPreviousStorages()
				.computeIfAbsent(p.getType(), t -> new HashMap<>()).put(p.getLocation(), p));

		// Fetch the remote prices stream
		nextStep(context, "disk-retrieve-catalog");
		try (CurlProcessor curl = new CurlProcessor()) {
			final String rawJson = StringUtils.defaultString(curl.get(getManagedDiskApi()), "{}");
			final ManagedDisks prices = objectMapper.readValue(rawJson, ManagedDisks.class);

			// Install related regions
			nextStep(context, "disk-update-catalog");
			prices.getRegions().stream().filter(r -> isEnabledRegion(context, r))
					.forEach(r -> installRegion(context, r));

			// Update or install storage price
			final Map<String, ManagedDisk> offers = prices.getOffers();
			context.setTransactions(offers.getOrDefault("transactions", new ManagedDisk()).getPrices());
			offers.entrySet().stream().filter(p -> !"transactions".equals(p.getKey()))
					.filter(p -> !p.getKey().startsWith("ultrassd")).forEach(o -> installStoragePrice(context, o));
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
		disk.getPrices().entrySet().stream().filter(p -> isEnabledRegion(context, p.getKey()))
				.forEach(p -> installStoragePrice(context, previousT, context.getRegions().get(p.getKey()), type,
						p.getValue().getValue(), offer.getKey()));
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
			final ProvStorageType type, final double value, final String typeCode) {
		final ProvStoragePrice price = regionPrices.computeIfAbsent(region, r -> {
			final ProvStoragePrice newPrice = new ProvStoragePrice();
			newPrice.setType(type);
			newPrice.setLocation(region);
			newPrice.setCode(region.getName() + "-az-" + type.getName());
			return newPrice;
		});
		// Fixed cost
		price.setCost(value);

		if (!typeCode.startsWith("premium")) {
			// Additional transaction based cost : $/10,000 transaction -> $/1,000,000 transaction
			price.setCostTransaction(Optional.ofNullable(context.getTransactions().get(region.getName()))
					.map(v -> round3Decimals(v.getValue() * 100)).orElse(0d));
		}
		spRepository.save(price);
		return price;
	}

	/**
	 * Install or update a storage type.
	 */
	private ProvStorageType installStorageType(final UpdateContext context, String name, final ManagedDisk disk) {
		final boolean isSnapshot = name.endsWith("snapshot");
		final ProvStorageType type = context.getStorageTypes().computeIfAbsent(
				name.replace("standardssd-", "").replace("standardhdd-", "").replace("premiumssd-", ""), n -> {
					final ProvStorageType newType = new ProvStorageType();
					newType.setNode(context.getNode());
					newType.setName(n);
					return newType;
				});

		// Merge storage type statistics
		return updateType(context, type, disk, isSnapshot, name);
	}

	/**
	 * Update the given storage type and persist it
	 */
	private ProvStorageType updateType(final UpdateContext context, final ProvStorageType type, final ManagedDisk disk,
			final boolean isSnapshot, final String code) {
		return context.getStorageTypesMerged().computeIfAbsent(type.getName(), n -> {
			if (isSnapshot) {
				type.setLatency(Rate.WORST);
				type.setMinimal(0);
				type.setOptimized(ProvStorageOptimized.DURABILITY);
				type.setIops(0);
				type.setThroughput(0);
			} else {
				// Complete data
				// https://docs.microsoft.com/en-us/azure/virtual-machines/windows/disk-scalability-targets
				final boolean isPremium = code.startsWith("premium");
				final boolean isStandard = code.startsWith("standard");
				type.setLatency(isPremium ? Rate.BEST : Rate.MEDIUM);
				type.setMinimal(disk.getSize());
				type.setMaximal(disk.getSize());
				type.setOptimized(isPremium ? ProvStorageOptimized.IOPS : null);
				type.setInstanceType("%");
				type.setIops(isStandard && disk.getIops() == 0 ? 500 : disk.getIops());
				type.setThroughput(isStandard && disk.getThroughput() == 0 ? 60 : disk.getThroughput());
			}

			// Save the changes
			return stRepository.save(type);
		});
	}
}
