// Batch B: MSW handlers for the workspace (auth-service) and a project's features (scenario-service).
import {http, HttpResponse} from 'msw';
import type {components} from '../../api/generated/scenario-service';
import {ids} from './auth';
import {acmeMembers, orgFor, projectsFor, type UserKey} from './workspace';
import {tags as defaultTags} from './scenario';

type S = components['schemas'];

export function workspaceHandlers(user: UserKey) {
    return [
        http.get('/api/orgs', () => HttpResponse.json([orgFor(user)])),
        http.get(`/api/orgs/${ids.acme}/projects`, () => HttpResponse.json(projectsFor(user))),
        http.get(`/api/orgs/${ids.acme}/members`, () => HttpResponse.json(acmeMembers)),
    ];
}

export interface FeatureWorld {
    features: S['FeatureSummary'][];
    details: Map<string, S['Feature']>;
    tags?: S['Tag'][];
    /** Every request the handlers saw, as "METHOD path?query" with the parsed JSON body. */
    requests: Array<{key: string; body: unknown}>;
}

export function featureWorld(features: S['FeatureSummary'][], details: S['Feature'][], tags: S['Tag'][] = defaultTags): FeatureWorld {
    return {features, details: new Map(details.map((d) => [d.id, d])), tags, requests: []};
}

/** listFeatures (with archived and tag filters), getFeature and listTags for any project. */
export function scenarioReadHandlers(world: FeatureWorld) {
    return [
        http.get('/api/projects/:projectId/features', ({request}) => {
            const url = new URL(request.url);
            world.requests.push({key: `GET ${url.pathname}${url.search}`, body: null});
            const archived = url.searchParams.get('archived') === 'true';
            const tag = url.searchParams.get('tag');
            return HttpResponse.json(
                world.features.filter((f) => (f.archivedAt !== null) === archived && (tag === null || f.tags.includes(tag))),
            );
        }),
        http.get('/api/projects/:projectId/features/:featureId', ({params}) => {
            const found = world.details.get(String(params.featureId));
            if (!found) {
                return HttpResponse.json(
                    {type: 'about:blank', title: 'Not Found', status: 404, detail: 'No feature.', instance: '/x', code: 'FEATURE_NOT_FOUND'},
                    {status: 404, headers: {'Content-Type': 'application/problem+json'}},
                );
            }
            return HttpResponse.json(found);
        }),
        http.get('/api/projects/:projectId/tags', () => HttpResponse.json(world.tags ?? [])),
    ];
}
