/*
 * Service layer for plugin "prov-azure".
 *
 * The legacy `service/prov/azure/azure.js` controller was empty
 * (`define({})`) — the Azure provisioning tool contributes no row
 * actions or detail chips of its own. Its entire UI surface is the
 * subscribe-wizard parameter labels shipped via i18n (see ./i18n).
 *
 * This module is therefore intentionally empty of render hooks. It is
 * kept (rather than dropped) so the plugin manifest's `service` field is
 * populated for contract parity with the other tool plugins, and so any
 * future Azure-specific row action has an obvious home.
 */
const service = {}

export default service
