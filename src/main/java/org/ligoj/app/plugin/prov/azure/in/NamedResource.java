package org.ligoj.app.plugin.prov.azure.in;

import org.ligoj.bootstrap.core.INamableBean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure named resource.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NamedResource implements INamableBean<String> {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	@JsonProperty("slug")
	private String id;

	@JsonProperty("displayName")
	private String name;
}
