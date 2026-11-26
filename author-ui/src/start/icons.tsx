import Apps from '@spectrum-icons/workflow/Apps';
import Box from '@spectrum-icons/workflow/Box';
import BoxAdd from '@spectrum-icons/workflow/BoxAdd';
import Branch1 from '@spectrum-icons/workflow/Branch1';
import Data from '@spectrum-icons/workflow/Data';
import Heart from '@spectrum-icons/workflow/Heart';
import History from '@spectrum-icons/workflow/History';
import Images from '@spectrum-icons/workflow/Images';
import Info from '@spectrum-icons/workflow/Info';
import Key from '@spectrum-icons/workflow/Key';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import Labels from '@spectrum-icons/workflow/Labels';
import InboxIcon from '@spectrum-icons/workflow/Inbox';
import LaunchIcon from '@spectrum-icons/workflow/Launch';
import ClockCheckIcon from '@spectrum-icons/workflow/ClockCheck';
import WorkflowIcon from '@spectrum-icons/workflow/Workflow';
import Plug from '@spectrum-icons/workflow/Plug';
import Project from '@spectrum-icons/workflow/Project';
import Settings from '@spectrum-icons/workflow/Settings';
import FilterIcon from '@spectrum-icons/workflow/Filter';
import Shield from '@spectrum-icons/workflow/Shield';
import User from '@spectrum-icons/workflow/User';
import UserGroup from '@spectrum-icons/workflow/UserGroup';
import GraphBarVertical from '@spectrum-icons/workflow/GraphBarVertical';
import Hammer from '@spectrum-icons/workflow/Hammer';
import Form from '@spectrum-icons/workflow/Form';
import Stopwatch from '@spectrum-icons/workflow/Stopwatch';
import BracketsIcon from '@spectrum-icons/workflow/Brackets';
import ChatIcon from '@spectrum-icons/workflow/Chat';
import CloudOutlineIcon from '@spectrum-icons/workflow/CloudOutline';
import FileCodeIcon from '@spectrum-icons/workflow/FileCode';
import AppsIcon from '@spectrum-icons/workflow/Apps';
import CodeIcon from '@spectrum-icons/workflow/Code';
import CollectionIcon from '@spectrum-icons/workflow/Collection';
import CropIcon from '@spectrum-icons/workflow/Crop';
import EmailGearIcon from '@spectrum-icons/workflow/EmailGear';
import GearsIcon from '@spectrum-icons/workflow/Gears';
import GlobeIcon from '@spectrum-icons/workflow/Globe';
import GlobeClockIcon from '@spectrum-icons/workflow/GlobeClock';
import ImageAutoModeIcon from '@spectrum-icons/workflow/ImageAutoMode';
import ImageProfileIcon from '@spectrum-icons/workflow/ImageProfile';
import LinkIcon from '@spectrum-icons/workflow/Link';
import MagicWandIcon from '@spectrum-icons/workflow/MagicWand';
import PropertiesIcon from '@spectrum-icons/workflow/Properties';
import SegmentationIcon from '@spectrum-icons/workflow/Segmentation';
import TargetIcon from '@spectrum-icons/workflow/Target';
import TaskListIcon from '@spectrum-icons/workflow/TaskList';
import TextEditIcon from '@spectrum-icons/workflow/TextEdit';
import UserActivityIcon from '@spectrum-icons/workflow/UserActivity';
import UserGroupIcon from '@spectrum-icons/workflow/UserGroup';
import type { ComponentType } from 'react';
import type { IconName } from './navigation';
import { RecordIcon, RecordModelIcon } from '../shell/recordIcons';
import { BlockIcon, PageIcon, TemplateIcon } from '../shell/pageIcons';

type IconProps = { size?: 'XS' | 'S' | 'M' | 'L' | 'XL' | 'XXL'; UNSAFE_className?: string };

const ICONS: Record<IconName, ComponentType<IconProps>> = {
  graph: GraphBarVertical,
  tools: Hammer,
  forms: Form,
  sites: PageIcon,
  assets: Images,
  xf: BlockIcon,
  cf: RecordIcon,
  projects: Project,
  repository: Data,
  packages: Box,
  bundles: Plug,
  config: Settings,
  installer: BoxAdd,
  jobs: Stopwatch,
  health: Heart,
  requests: History,
  resolver: Branch1,
  status: Info,
  users: User,
  groups: UserGroup,
  permissions: Shield,
  truststore: Key,
  ssl: LockClosed,
  oauth: Apps,
  tags: Labels,
  workflow: WorkflowIcon,
  wfinstances: ClockCheckIcon,
  wflaunchers: LaunchIcon,
  inbox: InboxIcon,
  templates: TemplateIcon,
  about: Info,
  live: GlobeIcon,
  release: GlobeClockIcon,
  link: LinkIcon,
  searchforms: FilterIcon,
  assetsconfig: Settings,
  translate: ChatIcon,
  review: TextEditIcon,
  jobsList: TaskListIcon,
  providers: CloudOutlineIcon,
  mail: EmailGearIcon,
  audience: TargetIcon,
  segments: SegmentationIcon,
  persona: UserActivityIcon,
  properties: PropertiesIcon,
  processing: ImageAutoModeIcon,
  crop: CropIcon,
  presets: ImageProfileIcon,
  autotags: MagicWandIcon,
  variations: MagicWandIcon,
  collection: CollectionIcon,
  code: CodeIcon,
  query: FileCodeIcon,
  components: AppsIcon,
  confbrowser: BracketsIcon,
  osgiconfig: GearsIcon,
  models: RecordModelIcon,
  usergroup: UserGroupIcon,
};

/**
 * A console's icon. `badge` puts a small second icon on its top-right corner, for a tile that is about the
 * first thing rather than the thing itself — Media's settings are the media icon with a gear on it.
 */
export function NavIcon({ name, size = 'L', badge }: { name: IconName; size?: IconProps['size']; badge?: IconName }) {
  const Icon = ICONS[name];
  if (!badge) return <Icon size={size} />;
  const Badge = ICONS[badge];
  const small = size === 'XL' || size === 'XXL' ? 'S' : 'XS';
  return (
    <span className={`studio-navicon studio-navicon--${size}`}>
      <Icon size={size} />
      <span className="studio-navicon__badge" aria-hidden>
        <Badge size={small} />
      </span>
    </span>
  );
}
