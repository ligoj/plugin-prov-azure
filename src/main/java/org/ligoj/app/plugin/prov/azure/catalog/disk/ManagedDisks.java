/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.disk;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzurePrice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Managed disk prices.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedDisks extends AbstractAzurePrice<ManagedDisk> {

	// All is delegated
}
