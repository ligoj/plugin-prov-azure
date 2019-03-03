/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.database;

import java.util.function.Function;
import java.util.regex.Matcher;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Database type configuration mapping.
 */
@AllArgsConstructor
@Getter
public class DbConfiguration {
	private Function<Matcher, String> toTier;
	private Function<Matcher, Integer> toGen;
	private Function<Matcher, Integer> toVcore;
	private Function<Matcher, String> toTerm;

}
