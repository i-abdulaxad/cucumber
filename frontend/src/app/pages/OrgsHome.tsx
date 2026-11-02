import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { authApi, unwrap } from '../../api/client';
import { EmptyState } from '../../ui/EmptyState';
import { PageHeading } from '../../ui/PageHeading';
import { QueryView } from '../../ui/QueryView';
import { appStrings } from '../strings';

/** The signed-in landing page (AC-1.3): the caller's organizations, with `myRole`. */
export function OrgsHome() {
  const query = useQuery({ queryKey: ['orgs'], queryFn: () => unwrap(authApi.GET('/api/orgs')) });
  return (
    <>
      <PageHeading>{appStrings.orgsTitle}</PageHeading>
      <QueryView
        query={query}
        label={appStrings.orgsLabel}
        isEmpty={(orgs) => orgs.length === 0}
        empty={
          <EmptyState title={appStrings.orgsEmpty} action={<Link to="/orgs/new">{appStrings.createOrg}</Link>}>
            <p>{appStrings.orgsEmptyHelp}</p>
          </EmptyState>
        }
      >
        {(orgs) => (
          <ul>
            {orgs.map((org) => (
              <li key={org.id}>
                <Link to="/orgs/$orgId/projects" params={{ orgId: org.id }}>
                  {org.name}
                </Link>{' '}
                <span>({appStrings.yourRole(org.myRole)})</span>
              </li>
            ))}
          </ul>
        )}
      </QueryView>
    </>
  );
}
