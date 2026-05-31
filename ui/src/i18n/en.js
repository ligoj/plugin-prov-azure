// Plugin-local translations merged into the host i18n store at install
// time. Keep keys flat (colon-separated for parameter ids) to match the
// host's convention — the subscribe wizard's `t(p.id)` reads them as-is.
//
// The legacy `azure.js` controller was empty (`define({})`): this tool
// plugin's entire UI contribution is the parameter labels below, which
// turn the raw parameter ids (`service:prov:azure:subscription`, …) into
// friendly form labels in the auto-rendered subscribe wizard.
export default {
  // Subscribe-wizard parameter labels (from src/main/resources/csv/parameter.csv).
  'service:prov:azure:subscription': 'Subscription',
  'service:prov:azure:application': 'Application ID',
  'service:prov:azure:key': 'Application Key',
  'service:prov:azure:tenant': 'Tenant ID',
  'service:prov:azure:resource-group': 'Resource Group',
  // Runtime/data labels also shipped by the legacy nls bundle.
  'service:prov:azure:name': 'Name',
  'service:prov:azure:location': 'Location',

  // Azure Support plan descriptions, keyed by the ids referenced from
  // src/main/resources/csv/azure-prov-support-type.csv (description column).
  // Plain text — vue-i18n's flat resolver returns the message verbatim.
  'service:prov:azure:support:developer':
    'The Developer Support plan offers resources for customers testing or doing early development on Azure: business-hours access to technical support for non-production workloads.',
  'service:prov:azure:support:business':
    'The Business (Standard) Support plan offers resources for customers running production workloads on Azure: 24x7 technical support and faster response times.',
  'service:prov:azure:support:enterprise':
    'The Enterprise (Professional Direct) Support plan offers resources for customers running business & mission critical workloads on Azure: proactive guidance and the fastest response times.',
}
