/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.disk;

import java.util.Collections;
import java.util.Map;

import org.ligoj.app.plugin.prov.azure.catalog.ValueWrapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * Price per region.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedDisk {

	/**
	 * Disk size in GiB.
	 */
	private int size;

	/**
	 * Advertised throughput: MB/s.
	 */
	@JsonProperty("speed")
	private int throughput;

	/**
	 * Advertised IOPS.
	 */
	private int iops;

	/**
	 * Price per regions. Key is the Azure region identifier. Value is the actual price.
	 */
	private Map<String, ValueWrapper> prices = Collections.emptyMap();

}
