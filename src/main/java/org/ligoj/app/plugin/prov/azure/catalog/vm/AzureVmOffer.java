/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.vm;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureOffer;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.VmOs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure VM prices for an instance type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureVmOffer extends AbstractAzureOffer {

	/**
	 * RAM, in GiB.
	 */
	private double ram;

	/**
	 * Optional disk size, in GiB
	 */
	private int diskSize;

	/**
	 * Resolved OS. May be <code>null</code>.
	 */
	@JsonIgnore
	private VmOs os;

	/**
	 * Resolved software. May be <code>null</code>.
	 */
	@JsonIgnore
	private String software;

	/**
	 * Resolved instance type. May be <code>null</code>.
	 */
	@JsonIgnore
	private ProvInstanceType type;

}
