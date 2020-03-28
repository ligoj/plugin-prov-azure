/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.Map;

import org.ligoj.app.plugin.prov.model.AbstractInstanceType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure offer for a compute type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class AbstractAzureOffer<T extends AbstractInstanceType> {

	/**
	 * Number of cores.
	 */
	private int cores;

	private String series;

	/**
	 * Resolved storage engine. May be <code>null</code>.
	 */
	@JsonIgnore
	private String storageEngine;
	/**
	 * Resolved edition. May be <code>null</code>.
	 */
	@JsonIgnore
	private String edition;

	/**
	 * Price per region. Key is the Azure region identifier or <code>global</code>. Value is the actual price.
	 */
	private Map<String, Map<String, ValueWrapper>> prices;

	/**
	 * Resolved instance type. May be <code>null</code>.
	 */
	@JsonIgnore
	private T type;
}
