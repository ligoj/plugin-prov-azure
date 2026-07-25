/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.azure.catalog.database;

import org.ligoj.app.plugin.azure.catalog.AbstractAzureOffer;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure Database prices for a database type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureDatabaseOffer extends AbstractAzureOffer<ProvDatabaseType> {

	/**
	 * RAM, in GiB. Only provided by some engines (PostgreSQL) for the flexible-server compute offers. When
	 * <code>0</code>, the RAM is derived from the size code.
	 */
	private double memory;
}
