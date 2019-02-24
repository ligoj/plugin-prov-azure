/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.support;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzureImport;
import org.ligoj.app.plugin.prov.azure.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.model.AbstractPrice;
import org.ligoj.app.plugin.prov.model.ProvSupportPrice;
import org.ligoj.app.plugin.prov.model.ProvSupportType;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.stereotype.Component;

/**
 * The provisioning support price service for Azure. Manage install or update of prices.<br>
 */
@Component
public class AzurePriceImportSupport extends AbstractAzureImport {

	@Override
	public void install(final UpdateContext context) throws IOException {
		nextStep(context, "support");

		// Install previous types
		installSupportTypes(context);

		// Fetch previous prices
		final Map<String, ProvSupportPrice> previous = sp2Repository.findAllBy("type.node", context.getNode()).stream()
				.collect(Collectors.toMap(AbstractPrice::getCode, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportPrice.class, "csv/azure-prov-support-price.csv").forEach(t -> {
			final ProvSupportPrice entity = previous.computeIfAbsent(t.getCode(), n -> t);

			// Merge the support type details
			entity.setCost(t.getCost());
			entity.setLimit(t.getLimit());
			entity.setMin(t.getMin());
			entity.setRate(t.getRate());

			sp2Repository.save(entity);

		});
	}

	private void installSupportTypes(final UpdateContext context) throws IOException {
		// Fetch previous prices
		final Map<String, ProvSupportType> previous = st2Repository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportType.class, "csv/azure-prov-support-type.csv").forEach(t -> {
			final ProvSupportType entity = previous.computeIfAbsent(t.getName(), n -> t);

			// Merge the support type details
			entity.setDescription(t.getDescription());
			entity.setAccessApi(t.getAccessApi());
			entity.setAccessChat(t.getAccessChat());
			entity.setAccessEmail(t.getAccessEmail());
			entity.setAccessPhone(t.getAccessPhone());
			entity.setSlaStartTime(t.getSlaStartTime());
			entity.setSlaEndTime(t.getSlaEndTime());
			entity.setDescription(t.getDescription());

			entity.setSlaBusinessCriticalSystemDown(t.getSlaBusinessCriticalSystemDown());
			entity.setSlaGeneralGuidance(t.getSlaGeneralGuidance());
			entity.setSlaProductionSystemDown(t.getSlaProductionSystemDown());
			entity.setSlaProductionSystemImpaired(t.getSlaProductionSystemImpaired());
			entity.setSlaSystemImpaired(t.getSlaSystemImpaired());
			entity.setSlaWeekEnd(t.isSlaWeekEnd());

			entity.setCommitment(t.getCommitment());
			entity.setSeats(t.getSeats());
			entity.setLevel(t.getLevel());
			st2Repository.save(entity);
		});
	}
}
