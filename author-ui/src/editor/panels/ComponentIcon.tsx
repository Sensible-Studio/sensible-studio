import Article from '@spectrum-icons/workflow/Article';
import Asset from '@spectrum-icons/workflow/Asset';
import BreadcrumbNavigation from '@spectrum-icons/workflow/BreadcrumbNavigation';
import Button from '@spectrum-icons/workflow/Button';
import Calendar from '@spectrum-icons/workflow/Calendar';
import Card from '@spectrum-icons/workflow/Card';
import Code from '@spectrum-icons/workflow/Code';
import Data from '@spectrum-icons/workflow/Data';
import Download from '@spectrum-icons/workflow/Download';
import Email from '@spectrum-icons/workflow/Email';
import Experience from '@spectrum-icons/workflow/Experience';
import Form from '@spectrum-icons/workflow/Form';
import Globe from '@spectrum-icons/workflow/Globe';
import Image from '@spectrum-icons/workflow/Image';
import ImageCarousel from '@spectrum-icons/workflow/ImageCarousel';
import Layers from '@spectrum-icons/workflow/Layers';
import Link from '@spectrum-icons/workflow/Link';
import Location from '@spectrum-icons/workflow/Location';
import Multiple from '@spectrum-icons/workflow/Multiple';
import Panel from '@spectrum-icons/workflow/Panel';
import Search from '@spectrum-icons/workflow/Search';
import Separator from '@spectrum-icons/workflow/Separator';
import Share from '@spectrum-icons/workflow/Share';
import Table from '@spectrum-icons/workflow/Table';
import TableHistogram from '@spectrum-icons/workflow/TableHistogram';
import Text from '@spectrum-icons/workflow/Text';
import TextAlignLeft from '@spectrum-icons/workflow/TextAlignLeft';
import TextBulleted from '@spectrum-icons/workflow/TextBulleted';
import ViewList from '@spectrum-icons/workflow/ViewList';
import type { ComponentType } from 'react';
import type { ComponentInfo } from '../api/StudioClient';

type Icon = ComponentType<{ size?: 'XS' | 'S' | 'M'; UNSAFE_className?: string; 'aria-hidden'?: boolean }>;

/** Coral icon names (`cq:icon`) of the Core Components and common project components → Spectrum icons. */
const ICONS: Record<string, Icon> = {
  article: Article,
  asset: Asset,
  breadcrumbNavigation: BreadcrumbNavigation,
  button: Button,
  calendar: Calendar,
  card: Card,
  code: Code,
  data: Data,
  download: Download,
  email: Email,
  experience: Experience,
  form: Form,
  globe: Globe,
  image: Image,
  imageCarousel: ImageCarousel,
  layers: Layers,
  link: Link,
  location: Location,
  multiple: Multiple,
  panel: Panel,
  search: Search,
  separator: Separator,
  share: Share,
  table: Table,
  tableHistogram: TableHistogram,
  text: Text,
  textLeft: TextAlignLeft,
  textBulleted: TextBulleted,
  viewList: ViewList,
};

/** Is there an icon for the component's `cq:icon`? */
export function hasComponentIcon(c: Pick<ComponentInfo, 'icon'>): boolean {
  return !!c.icon && !!ICONS[c.icon];
}

/** The component's icon, as AEM's component browser shows it: its `cq:icon`, else its abbreviation. */
export function ComponentIcon({ component, abbreviation }: { component: Pick<ComponentInfo, 'icon'>; abbreviation: string }) {
  const I = component.icon ? ICONS[component.icon] : undefined;
  return I ? (
    <span className="studio-complist__icon is-icon" aria-hidden>
      <I size="S" />
    </span>
  ) : (
    <span className="studio-complist__icon" aria-hidden>
      {abbreviation}
    </span>
  );
}
