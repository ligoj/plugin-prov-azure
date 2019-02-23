/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.HashMap;
import java.util.Map;

import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;

import lombok.Getter;
import lombok.Setter;

/**
 * Context used to perform catalog update.
 */
public class UpdateContext extends AbstractUpdateContext {

	/**
	 * The previous installed storage prices.
	 */
	@Getter
	@Setter
	private Map<ProvStorageType, Map<ProvLocation, ProvStoragePrice>> previousStorages;

	/**
	 * The previous installed "lowpriority" prices.
	 */
	@Getter
	@Setter
	private Map<String, ProvInstancePrice> previousLowPriority;

	/**
	 * The merged (updated properties) available regions.
	 */
	@Getter
	private Map<String, ProvLocation> mergedRegions = new HashMap<>();

	/**
	 * The transaction based cost by region. Key is the region name.
	 */
	@Getter
	@Setter
	private Map<String, ValueWrapper> transactions;

}
