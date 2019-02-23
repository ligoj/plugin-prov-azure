/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvLocation;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 */
public abstract class AbstractAzureImport extends AbstractImportCatalogResource {

	/**
	 * Configuration key used for Azure URL prices.
	 */
	protected static final String CONF_API_PRICES = ProvAzurePluginResource.KEY + ":prices-url";

	/**
	 * Default pricing URL.
	 */
	protected static final String DEFAULT_API_PRICES = "https://azure.microsoft.com/api/v2/pricing";

	/**
	 * Mapping from API region identifier to region name.
	 */
	private Map<String, ProvLocation> mapRegionToName = new HashMap<>();

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final NamedResource region) {
		return isEnabledRegion(context, region.getId());
	}

	/**
	 * Update the current phase for statistics and add 1 to the processed workload.
	 *
	 * @param context
	 *            The current import context.
	 * @param phase
	 *            The new import phase.
	 */
	protected void nextStep(final UpdateContext context, final String phase) {
		nextStep(context.getNode(), phase);
	}

	/**
	 * Update the current phase for statistics and add 1 to the processed workload.
	 *
	 * @param node
	 *            The current import node.
	 * @param phase
	 *            The new import phase.
	 */
	protected void nextStep(final Node node, final String phase) {
		importCatalogResource.nextStep(node.getId(), t -> {
			importCatalogResource.updateStats(t);
			t.setWorkload(getWorkload(t));
			t.setDone(t.getDone() + 1);
			t.setPhase(phase);
		});
	}

	/**
	 * Install or update prices.
	 *
	 * @param context
	 *            The current import context.
	 * @throws IOException
	 *             When prices cannot be remotely read.
	 */
	public abstract void install(UpdateContext context) throws IOException;

	@Override
	protected int getWorkload(final ImportCatalogStatus status) {
		return 32; // 1 region, 3 disk, 1 support, 3*3*3 VM
	}

	/**
	 * Install a new region.<br/>
	 * Also see CLI2 command <code>az account list-locations</code>
	 *
	 * @param context
	 *            The current import context.
	 * @param region
	 *            The region bundled code and human name to install as needed.
	 * @return The previous or the new installed region.
	 */
	protected ProvLocation installRegion(final UpdateContext context, final NamedResource region) {
		return installRegion(context, region.getId(), region.getName());
	}

	/**
	 * Install a new region as needed.<br/>
	 * Also see CLI2 command <code>az account list-locations</code>
	 *
	 * @param context
	 *            The current import context.
	 * @param region
	 *            The region code to install as needed.
	 * @param name
	 *            The region human name.
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
}
