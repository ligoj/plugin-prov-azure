/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ligoj.app.plugin.prov.azure.catalog.database.DbConfiguration;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
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
	/**
	 * The merged (updated properties) available regions.
	 */
	@Getter
	private Map<String, ProvLocation> mergedRegions = new HashMap<>();

	/**
	 * The HDD transaction based cost by region. Key is the region name.
	 */
	@Getter
	@Setter
	private Map<String, ValueWrapper> transactionsHdd;

	/**
	 * The SSD transaction based cost by region. Key is the region name.
	 */
	@Getter
	@Setter
	private Map<String, ValueWrapper> transactionsSsd;

	/**
	 * The mapping from the Azure price entry to the database instance type name.
	 */
	@Getter
	@Setter
	private Map<Pattern, DbConfiguration> toDatabase;

	/**
	 * The mapping from the Azure price entry to the storage type name.
	 */
	@Getter
	@Setter
	private Map<Pattern, Function<Matcher,String>> toStorage;

	/**
	 * Static storage type definition.
	 */
	@Getter
	@Setter
	private Map<String, ProvStorageType> storageTypesStatic;
}
