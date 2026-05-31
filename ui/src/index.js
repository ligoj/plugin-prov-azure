/*
 * Plugin "prov-azure" — Azure implementation of plugin-prov.
 *
 * Tool-level plugin: lives at `service:prov:azure` in the node tree. It
 * does not own routes, a component, or render hooks (the legacy
 * `azure.js` was an empty `define({})`). Its sole contribution is the
 * Azure-specific i18n so the parent `plugin-prov`'s subscribe wizard
 * renders friendly parameter labels and support-plan descriptions.
 *
 * Authored as source — compiled to `/main/prov-azure/vue/index.js` by
 * Vite. Shared host surface (stores) is imported from `@ligoj/host` and
 * kept external at build so plugin and host share the same instances.
 */
import { useI18nStore } from '@ligoj/host'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

// No features today (the legacy controller was empty). The dispatcher is
// kept for contract parity: every plugin exposes a `feature(action, …)`
// entry point, and it throws clearly for any unknown action.
const features = {}

export default {
  id: 'prov-azure',
  label: 'Azure',
  // Declared dependency: the parent service-level plugin owns the
  // `/prov/*` routes and the subscribe wizard that renders our parameter
  // labels. The loader awaits requires before calling our install(), so
  // the parent's bundle is in the store before our i18n merges in.
  requires: ['prov'],
  // No routes / component — Azure screens and the parameter form come
  // from the parent's wizard.
  install() {
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "prov-azure" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-microsoft-azure', color: 'blue-darken-2' },
}

export { service }
