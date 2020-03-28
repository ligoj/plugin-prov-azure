/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.vm;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureOffer;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure VM prices for an instance type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureVmOffer extends AbstractAzureOffer<ProvInstanceType> {

	/**
	 * RAM, in GiB.
	 */
	private double ram;

	/**
	 * Optional disk size, in GiB
	 */
	private int diskSize;

}
