/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 */
@Slf4j
public abstract class AbstractAzureImport extends AbstractImportCatalogResource {

	private static final String TERM_LOW = "lowpriority";

	/**
	 * Default term code name.
	 */
	protected static final String DEFAULT_TERM = "payg";

	/**
	 * Configuration key used for Azure URL prices.
	 */
	protected static final String CONF_API_PRICES = ProvAzurePluginResource.KEY + ":prices-url";

	/**
	 * Default pricing URL.
	 */
	protected static final String DEFAULT_API_PRICES_V3 = "https://azure.microsoft.com/api/v3/pricing";

	/**
	 * Default pricing URL.
	 */
	protected static final String DEFAULT_API_PRICES_V2 = "https://azure.microsoft.com/api/v2/pricing";

	/**
	 * Mapping from API region identifier to region name.
	 */
	private Map<String, ProvLocation> mapRegionToName = new HashMap<>();

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context The current import context.
	 * @param region  The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final NamedResource region) {
		return isEnabledRegion(context, region.getId());
	}

	/**
	 * Update the current phase for statistics and add 1 to the processed workload.
	 *
	 * @param context The current import context.
	 * @param phase   The new import phase.
	 */
	protected void nextStep(final UpdateContext context, final String phase) {
		nextStep(context.getNode(), phase);
	}

	/**
	 * Install or update prices.
	 *
	 * @param context The current import context.
	 * @throws IOException When prices cannot be remotely read.
	 */
	public abstract void install(UpdateContext context) throws IOException;

	@Override
	protected int getWorkload(final ImportCatalogStatus status) {
		return 44; // 1 (global) region, 3 disk, 4 engine x3 phases, 1 support, 3 tiers x3 phases x3 VM term
	}

	/**
	 * Install a new region.<br>
	 * Also see CLI2 command <code>az account list-locations</code>
	 *
	 * @param context The current import context.
	 * @param region  The region bundled code and human name to install as needed.
	 * @return The previous or the new installed region.
	 */
	protected ProvLocation installRegion(final UpdateContext context, final NamedResource region) {
		return installRegion(context, region.getId(), region.getName());
	}

	/**
	 * Install a new region as needed.<br>
	 * Also see CLI2 command <code>az account list-locations</code>
	 *
	 * @param context The current import context.
	 * @param region  The region code to install as needed.
	 * @param name    The region human name.
	 * @return The previous or the new installed region.
	 */
	protected ProvLocation installRegion(final UpdateContext context, final String region, final String name) {
		final ProvLocation entity = context.getRegions().computeIfAbsent(region, r -> {
			final ProvLocation newRegion = new ProvLocation();
			newRegion.setNode(context.getNode());
			newRegion.setName(region);
			return newRegion;
		});

		// Update the location details as needed
		return context.getMergedRegions().computeIfAbsent(region, r -> {
			final ProvLocation regionStats = mapRegionToName.getOrDefault(r, new ProvLocation());
			entity.setContinentM49(regionStats.getContinentM49());
			entity.setCountryM49(regionStats.getCountryM49());
			entity.setCountryA2(regionStats.getCountryA2());
			entity.setPlacement(regionStats.getPlacement());
			entity.setRegionM49(regionStats.getRegionM49());
			entity.setSubRegion(regionStats.getSubRegion());
			entity.setLatitude(regionStats.getLatitude());
			entity.setLongitude(regionStats.getLongitude());
			entity.setDescription(name);
			locationRepository.saveAndFlush(entity);
			return entity;
		});
	}

	/**
	 * Install a new price term as needed and complete the specifications.
	 */
	protected ProvInstancePriceTerm installPriceTerm(final UpdateContext context, final AbstractAzurePrice<?> prices,
			final String termId, final String sku) {
		final var code = sku.endsWith("-lowpriority") ? TERM_LOW
				: StringUtils.defaultIfEmpty(StringUtils.removeStart(termId, "ahb"), DEFAULT_TERM);
		final var term = context.getPriceTerms().computeIfAbsent(code, t -> {
			final var newTerm = new ProvInstancePriceTerm();
			newTerm.setNode(context.getNode());
			newTerm.setCode(t);
			return newTerm;
		});

		// Complete the specifications
		return copyAsNeeded(context, term, t -> {
			t.setName(Objects.requireNonNullElse(prices.getTiersById().getOrDefault(code, prices.getBillingById().get(code)),code));
			t.setPeriod(toPeriod(code));
			t.setReservation(t.getPeriod() > 0);
			t.setConvertibleFamily(t.getReservation());
			t.setConvertibleType(t.getReservation());
			t.setConvertibleLocation(t.getReservation());
			t.setConvertibleOs(t.getReservation());
			t.setEphemeral(code.equals(TERM_LOW) || t.getCode().equals("spot"));
		}, iptRepository);
	}

	/**
	 * Return the period duration from the term name.
	 */
	private int toPeriod(final String code) {
		if (code.contains("three")) {
			return 36;
		}
		if (code.contains("five")) {
			return 60;
		}
		return code.contains("one") ? 12 : 0;
	}

	protected void commonPreparation(final UpdateContext context, final AbstractAzurePrice<?> prices) {
		// Install related regions
		prices.getRegions().stream().filter(r -> isEnabledRegion(context, r)).forEach(r -> installRegion(context, r));

		// Build maps
		prices.getTiers().forEach(n -> prices.getTiersById().put(n.getId(), n.getName()));
		prices.getBillingOptions().forEach(n -> prices.getBillingById().put(n.getId(), n.getName()));
	}

	protected static final int PER_CORE = 0;
	protected static final int PER_HOUR = 1;
	protected static final int PER_MONTH = 2;

	protected void updateCostCounters(final double[] costs, final String tiers, final String sku, final double value) {
		if ("percoreperhour".equals(tiers)) {
			costs[PER_CORE] += value;
		} else if (tiers.startsWith("perhour")) {
			costs[PER_HOUR] += value;
		} else if (tiers.startsWith("permonth")) {
			costs[PER_MONTH] += value;
		} else {
			log.error("Unknown pricing tier {} in SKU {}", tiers, sku);
		}
	}

	protected String toSizeName(final UpdateContext context, final String id) {
		return StringUtils.defaultString(context.getSizesById().get(id), id);
	}
}
