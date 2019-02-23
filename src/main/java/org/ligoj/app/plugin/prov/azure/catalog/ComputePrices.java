/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure compute prices for a fixed term.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComputePrices extends AbstractAzurePrice<AzureVmPrice> {

	// All is delegated
}