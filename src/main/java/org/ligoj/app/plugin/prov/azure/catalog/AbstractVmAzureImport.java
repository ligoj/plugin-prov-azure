/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import org.apache.commons.lang3.ObjectUtils;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 */
@Slf4j
public abstract class AbstractVmAzureImport<T extends AbstractInstanceType> extends AbstractAzureImport {

	protected <A extends AbstractAzurePrice<? extends AbstractAzureOffer<T>>> void checkComponents(
			final UpdateContext context, final A prices, final List<String> components, final String sku,
			final String termName, final BiPredicate<UpdateContext, String> typeFilter,
			final FiveConsumer<T, String, String, Double, String> callback) {
		T type = null;
		final Map<String, double[]> localCosts = new HashMap<>();
		String edition = null;
		String storageEngine = null;
		final double[] globalCosts = new double[3];
		for (final var component : components) {
			final var parts = component.split("--");
			if (parts.length != 2) {
				// Any invalid part invalidates the list
				log.error("Invalid price {} found for SKU {} in term {}", component, sku, termName);
				return;
			}
			final var offerId = parts[0];
			final var offer = prices.getOffers().get(offerId);
			if (offer == null) {
				// Any invalid part invalidates the list
				log.error("Invalid offer reference {} found for SKU {} in term {}", offerId, sku, termName);
				return;
			}

			final var tiers = parts[1];
			final var localPrices = offer.getPrices().get(tiers);
			if (localPrices == null) {
				// Any invalid part invalidates the list
				log.error("Invalid tiers reference {} found for SKU {} in term {}", tiers, sku, termName);
				return;
			}
			if (localPrices.containsKey("global")) {
				updateCostCounters(globalCosts, tiers, sku, localPrices.get("global").getValue());
			} else {
				localPrices.entrySet().stream().filter(e -> isEnabledRegion(context, e.getKey()))
						.forEach(e -> updateCostCounters(localCosts.computeIfAbsent(e.getKey(), r -> new double[3]),
								tiers, sku, e.getValue().getValue()));
				type = ObjectUtils.defaultIfNull(offer.getType(), type);
				edition = ObjectUtils.defaultIfNull(offer.getEdition(), edition);
				storageEngine = ObjectUtils.defaultIfNull(offer.getStorageEngine(), storageEngine);
			}
		}
		if (type == null) {
			// Any invalid part invalidates the list
			log.error("Unresolved type found for SKU {} in term {}", sku, termName);
			return;
		}
		if (!typeFilter.test(context, type.getCode())) {
			// Ignored type
			return;
		}

		// Compute global prices
		final var globalCost = toMonthlyCost(context, type, globalCosts);

		// Iterate over regions enabling this instance type
		final var typeF = type;
		final var editionF = edition;
		final var storageEngineF = storageEngine;
		localCosts.forEach((r, costs) -> callback.accept(typeF, editionF, storageEngineF,
				toMonthlyCost(context, typeF, costs) + globalCost, r));
	}

	private double toMonthlyCost(final UpdateContext context, T type, double[] costs) {
		return costs[PER_MONTH] + (costs[PER_HOUR] + costs[PER_CORE] * type.getCpu()) * context.getHoursMonth();
	}
}
