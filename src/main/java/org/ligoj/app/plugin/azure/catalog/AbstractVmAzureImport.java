/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.azure.catalog;

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

	/**
	 * Indicate no component of this SKU can resolve an instance type: either a standalone software or storage or
	 * backup or IOPS dimension (like <code>autodesk-3ds-max</code>, <code>flexible-server-backup</code> or
	 * <code>additional-iops</code>), or a SKU whose whole series is announced in the catalog but not yet priced (no
	 * published compute offer). Such SKU can never produce an instance price and is silently skipped: dedicated
	 * dimensions like storage are imported from their offer definition, not from the SKUs.
	 *
	 * @param prices   The current catalog.
	 * @param skuTerms The SKU term definitions, the components per term name.
	 * @return <code>true</code> when this SKU cannot resolve any instance type.
	 */
	protected boolean isTypeless(final AbstractAzurePrice<? extends AbstractAzureOffer<T>> prices,
			final Map<String, List<String>> skuTerms) {
		return skuTerms.values().stream().flatMap(List::stream)
				.map(component -> prices.getOffers().get(component.split("--")[0]))
				.noneMatch(offer -> offer != null && offer.getType() != null);
	}

	protected <A extends AbstractAzurePrice<? extends AbstractAzureOffer<T>>> void installSkuComponents(
			final UpdateContext context, final A prices, final List<String> components, final String sku,
			final String termName, final BiPredicate<UpdateContext, String> typeFilter,
			final FiveConsumer<T, String, String, Double, String> callback) {
		T type = null;
		final Map<String, double[]> localCosts = new HashMap<>();
		String edition = null;
		String storageEngine = null;
		final var globalCosts = new double[3];
		for (final var component : components) {
			final var parts = component.split("--");
			if (parts.length != 2) {
				// Any invalid part invalidates the list
				log.error("Invalid price {} found for SKU {} in term {}", component, sku, termName);
				return;
			}
			final var offerId = parts[0];
			final var offer = prices.getOffers().get(offerId);
			if (offer == null || offer.getPrices() == null) {
				// Any invalid part invalidates the list
				logMissingReference(context, "offer", offerId, sku, termName);
				return;
			}

			final var tiers = parts[1];
			final var localPrices = offer.getPrices().get(tiers);
			if (localPrices == null) {
				// Any invalid part invalidates the list
				logMissingReference(context, "tiers", offerId + "--" + tiers, sku, termName);
				return;
			}
			if (localPrices.containsKey("global")) {
				updateCostCounters(globalCosts, tiers, sku, localPrices.get("global").getValue());
			} else {
				localPrices.entrySet().stream().filter(e -> isEnabledRegion(context, e.getKey()))
						.forEach(e -> updateCostCounters(localCosts.computeIfAbsent(e.getKey(), r -> new double[3]),
								tiers, sku, e.getValue().getValue()));
				type = ObjectUtils.getIfNull(offer.getType(), type);
				edition = ObjectUtils.getIfNull(offer.getEdition(), edition);
				storageEngine = ObjectUtils.getIfNull(offer.getStorageEngine(), storageEngine);
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

	/**
	 * Report an unresolvable offer or tiers reference. The Azure catalog regularly publishes SKU terms referencing
	 * offers or tiers not (yet) available in the same catalog: partially published series, or spot-only offers still
	 * referenced by on-demand terms. Only this term is skipped, the other terms of the SKU are priced as usual. This
	 * is an expected upstream state: each unique reference is reported once at DEBUG level, and only the amount of
	 * skipped references is reported at INFO level at the end of the import.
	 */
	private void logMissingReference(final UpdateContext context, final String kind, final String reference,
			final String sku, final String termName) {
		if (context.getReportedMissingReferences().add(kind + ":" + reference)) {
			log.debug("Invalid {} reference {} found for SKU {} in term {}, next occurrences are silently skipped",
					kind, reference, sku, termName);
		}
	}
}
