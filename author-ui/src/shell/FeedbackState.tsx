import { ActionButton, ProgressCircle } from '@adobe/react-spectrum';
import Alert from '@spectrum-icons/workflow/Alert';
import Info from '@spectrum-icons/workflow/Info';
import type { ReactNode } from 'react';

interface StateProps {
  title: string;
  children: ReactNode;
  action?: { label: string; onPress(): void };
  icon?: ReactNode;
  className?: string;
}

function FeedbackState({ title, children, action, icon, className = '' }: StateProps) {
  return (
    <div className={`studio-feedback ${className}`} role={className.includes('error') ? 'alert' : 'status'}>
      <span className="studio-feedback__icon" aria-hidden>{icon}</span>
      <h2>{title}</h2>
      <p>{children}</p>
      {action && <ActionButton onPress={action.onPress}>{action.label}</ActionButton>}
    </div>
  );
}

export function EmptyState(props: Omit<StateProps, 'className'>) {
  return <FeedbackState {...props} icon={props.icon ?? <Info />} className="studio-feedback--empty" />;
}

export function ErrorState(props: Omit<StateProps, 'className'>) {
  return <FeedbackState {...props} icon={props.icon ?? <Alert />} className="studio-feedback--error" />;
}

export function LoadingState({ label = 'Loading' }: { label?: string }) {
  return <div className="studio-feedback studio-feedback--loading" role="status"><ProgressCircle isIndeterminate aria-label={label} /><span>{label}</span></div>;
}
