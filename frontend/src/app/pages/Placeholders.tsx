// Batch A placeholders: later batches replace these pages (routes.tsx is additive).
import { PageHeading } from '../../ui/PageHeading';
import { AppLink } from '../../ui/AppLink';
import { appStrings } from '../strings';

export function NewOrgPlaceholder() {
  return (
    <>
      <PageHeading>{appStrings.newOrgTitle}</PageHeading>
      <p>{appStrings.laterBatch}</p>
    </>
  );
}

export function ProjectsPlaceholder() {
  return (
    <>
      <PageHeading>{appStrings.projectsTitle}</PageHeading>
      <p>{appStrings.laterBatch}</p>
    </>
  );
}

export function FeaturesPlaceholder({ tag }: { tag: string | undefined }) {
  return (
    <>
      <PageHeading>{appStrings.featuresTitle}</PageHeading>
      {tag ? <p>{appStrings.tagFilter(tag)}</p> : null}
      <p>{appStrings.laterBatch}</p>
    </>
  );
}

export function NotFoundPage() {
  return (
    <>
      <PageHeading>{appStrings.notFoundTitle}</PageHeading>
      <p>{appStrings.notFoundText}</p>
      <AppLink href="/">{appStrings.backToOrgs}</AppLink>
    </>
  );
}
