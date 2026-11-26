import { AssetsApp } from '../assets/AssetsApp';
import { GraphQLConsole } from '../graphql/GraphQLConsole';
import { QueryEditor } from '../graphql/QueryEditor';
import { HttpGraphQLClient } from '../graphql/api';
import { DamHttpClient } from '../assets/api';
import { CfConsole } from '../cf/CfConsole';
import { CfEditor } from '../cf/CfEditor';
import { CfModelEditor } from '../cf/CfModelEditor';
import { CfModels } from '../cf/CfModels';
import { HttpCfClient } from '../cf/api';
import type { StudioClient } from '../editor/api/StudioClient';
import { App } from '../editor/App';
import { PagePropertiesApp } from '../pageprops/PagePropertiesApp';
import { HttpReplicationClient } from '../replication/api';
import { CreateTemplateWizard } from '../templates/CreateTemplateWizard';
import { TemplateProperties } from '../templates/TemplateProperties';
import { TemplatesConsole } from '../templates/TemplatesConsole';
import { HttpTemplatesClient } from '../templates/api';
import { SitesConsole } from '../sites/SitesConsole';
import { StartPage } from '../start/StartPage';
import { HttpLaunchesClient } from '../launches/api';
import { LaunchesConsole } from '../launches/LaunchesConsole';
import { HttpLinkCheckerClient } from '../linkchecker/api';
import { LinkCheckerConsole } from '../linkchecker/LinkCheckerConsole';
import { ActivityConsole } from '../activity/ActivityConsole';
import { HttpProjectsClient } from '../projects/api';
import { ProjectsConsole } from '../projects/ProjectsConsole';
import { HttpSiteConfigClient } from '../siteconfig/api';
import { SiteConfigConsole } from '../siteconfig/SiteConfigConsole';
import { HttpHealthClient } from '../health/api';
import { HealthConsole } from '../health/HealthConsole';
import { HttpJobsClient } from '../jobs/api';
import { JobsConsole } from '../jobs/JobsConsole';
import { HttpMsmClient } from '../msm/MsmClient';
import { ReplicationConsole } from '../replication/ReplicationConsole';
import { SecurityApp } from '../security/SecurityApp';
import { HttpTagsClient } from '../tags/api';
import { TagsConsole } from '../tags/TagsConsole';
import { ActivitiesConsole } from '../targeting/ActivitiesConsole';
import { ContextHubConsole } from '../targeting/ContextHubConsole';
import { AudiencesConsole } from '../targeting/AudiencesConsole';
import { HttpTargetingClient } from '../targeting/api';
import { HttpTranslationConfigClient } from '../translation/api';
import { HttpPageTranslationClient } from '../translation/pages';
import { HttpTranslationProjectClient } from '../translation/projects';
import { TranslationConsole } from '../translation/TranslationConsole';
import { TranslationProjects } from '../translation/TranslationProjects';
import { TranslationReview } from '../translation/TranslationReview';
import { WorkflowApp } from '../workflow/WorkflowApp';
import type { Place, PlaceKind } from './places';
import { ComponentDetails, ComponentsConsole } from '../components/ComponentsConsole';
import { HttpComponentsClient } from '../components/api';
import { BlueprintsConsole } from '../msm/BlueprintsConsole';
import { TranslationContextEditor, TranslationContexts } from '../translation/TranslationRules';
import { GenerateVariations } from '../variations/GenerateVariations';
import { HttpVariationsClient } from '../variations/api';
import { HttpTranslationRulesClient } from '../translation/rules';
import { HttpSubmissionsClient } from '../forms/api';
import { MailSettings } from '../forms/MailSettings';
import { SubmissionsView } from '../forms/SubmissionsView';

/**
 * The kinds the shell renders as views in its tabs. Everything else still loads its own screen, until
 * it is converted; a `navigate()` to one of those is a page load.
 */
export const SHELL_KINDS: ReadonlySet<PlaceKind> = new Set<PlaceKind>([
  'start',
  'sites',
  'editor',
  'page-properties',
  'templates',
  'assets',
  'cf',
  'cf-editor',
  'cf-models',
  'cf-model-editor',
  'workflow',
  'replication',
  'launches',
  'linkchecker',
  'health',
  'jobs',
  'activity',
  'siteconfig',
  'projects',
  'form-submissions',
  'mail',
  'graphql',
  'components',
  'blueprints',
  'component',
  'graphiql',
  'audiences',
  'contexthub',
  'activities',
  'tags',
  'security',
  'translation-configs',
  'translation-rules',
  'variations',
  'translation-rule',
  'translation-review',
  'translation-projects',
]);

/** Views that stay mounted while their tab is in the background (an editor keeps its frame and its state). */
export const KEEP_ALIVE: ReadonlySet<PlaceKind> = new Set<PlaceKind>(['editor', 'sites', 'assets']);

/** How many background editors stay mounted before the oldest is let go. */
export const MAX_BACKGROUND_EDITORS = 3;

const templatesClient = new HttpTemplatesClient();
const projectsClient = new HttpProjectsClient();
const damClient = new DamHttpClient();
const cfClient = new HttpCfClient();
const graphqlClient = new HttpGraphQLClient();
const componentsClient = new HttpComponentsClient();
const msmClient = new HttpMsmClient();
const translationRulesClient = new HttpTranslationRulesClient();
const variationsClient = new HttpVariationsClient();

/** The view of a place. The view instance survives moves inside its own kind (a folder change in Sites). */
export function View({ place, client }: { place: Place; client: StudioClient }) {
  switch (place.kind) {
    case 'cf':
      return <CfConsole client={cfClient} studio={client} initialPath={place.path} />;
    case 'cf-editor':
      return <CfEditor key={place.path} client={cfClient} path={place.path} />;
    case 'cf-models':
      return <CfModels client={cfClient} initialPath={place.conf} />;
    case 'cf-model-editor':
      return <CfModelEditor key={place.path} client={cfClient} path={place.path} />;
    case 'workflow':
      return <WorkflowApp studio={client} route={place.route} />;
    case 'replication':
      return <ReplicationConsole client={new HttpReplicationClient()} studio={client} />;
    case 'form-submissions':
      return <SubmissionsView key={place.form} client={new HttpSubmissionsClient()} form={place.form} />;
    case 'mail':
      return <MailSettings />;
    case 'blueprints':
      return <BlueprintsConsole client={msmClient} />;
    case 'components':
      return <ComponentsConsole client={componentsClient} />;
    case 'component':
      return <ComponentDetails key={place.path} client={componentsClient} path={place.path} />;
    case 'graphql':
      return <GraphQLConsole client={graphqlClient} />;
    case 'graphiql':
      return <QueryEditor key={`${place.endpoint}:${place.query}`} client={graphqlClient} endpoint={place.endpoint} query={place.query} />;
    case 'activity':
      return <ActivityConsole />;
    case 'projects':
      return <ProjectsConsole client={projectsClient} name={place.name} />;
    case 'siteconfig':
      return <SiteConfigConsole client={new HttpSiteConfigClient()} path={place.path} />;
    case 'health':
      return <HealthConsole client={new HttpHealthClient()} currentUser={() => client.currentUser()} />;
    case 'jobs':
      return <JobsConsole client={new HttpJobsClient()} currentUser={() => client.currentUser()} />;
    case 'linkchecker':
      return <LinkCheckerConsole client={new HttpLinkCheckerClient()} currentUser={() => client.currentUser()} />;
    case 'launches':
      return <LaunchesConsole client={new HttpLaunchesClient()} msm={new HttpMsmClient()} studio={client} />;
    case 'contexthub':
      return <ContextHubConsole client={new HttpTargetingClient()} studio={client} initialConf={place.conf ?? undefined} />;
    case 'audiences':
      return <AudiencesConsole client={new HttpTargetingClient()} studio={client} initialConf={place.conf ?? undefined} />;
    case 'activities':
      return <ActivitiesConsole client={new HttpTargetingClient()} studio={client} initialRoot={place.root ?? undefined} />;
    case 'tags':
      return <TagsConsole client={new HttpTagsClient()} initialPath={place.path} />;
    case 'security':
      return <SecurityApp client={client} route={place.route} id={place.id} principal={place.principal} />;
    case 'translation-rules':
      return <TranslationContexts client={translationRulesClient} />;
    case 'variations':
      return <GenerateVariations client={variationsClient} />;
    case 'translation-rule':
      return <TranslationContextEditor key={place.context ?? '+'} client={translationRulesClient} context={place.context} />;
    case 'translation-configs':
      return <TranslationConsole client={new HttpTranslationConfigClient()} initialPath={place.path} />;
    case 'translation-projects':
      return <TranslationProjects client={new HttpTranslationProjectClient()} pages={new HttpPageTranslationClient()} initialName={place.name} />;
    case 'translation-review':
      return <TranslationReview client={new HttpPageTranslationClient()} initialPath={place.path} />;
    case 'assets':
      return <AssetsApp client={damClient} initial={place.route} />;
    case 'page-properties':
      return <PagePropertiesApp key={place.item} client={client} page={place.item} />;
    case 'templates': {
      const r = place.route;
      if (r.screen === 'console') return <TemplatesConsole client={templatesClient} studio={client} replication={new HttpReplicationClient()} initialConf={r.conf} />;
      if (r.screen === 'wizard') return <CreateTemplateWizard key={r.conf} client={templatesClient} conf={r.conf} />;
      return <TemplateProperties key={r.template} client={templatesClient} dam={cfClient} template={r.template} />;
    }
    case 'start':
      return <StartPage client={client} />;
    case 'sites':
      return <SitesConsole client={client} initialPath={place.path} />;
    case 'editor':
      return <App client={client} initialPage={place.path} />;
    default:
      return null;
  }
}
