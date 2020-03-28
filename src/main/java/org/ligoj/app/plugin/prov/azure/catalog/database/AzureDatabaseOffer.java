/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.database;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureOffer;
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

	// All delegated
}
