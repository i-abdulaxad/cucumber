import {scenarioApi, unwrap} from '../../api/client';

export const featuresKey = (projectId: string, archived: boolean, tag: string | undefined) =>
    ['features', projectId, {archived, tag: tag ?? null}] as const;

export function listFeatures(projectId: string, archived: boolean, tag: string | undefined) {
    return unwrap(
        scenarioApi.GET('/api/projects/{projectId}/features', {
            params: {path: {projectId}, query: {...(archived ? {archived: 'true'} : {}), ...(tag ? {tag} : {})}},
        }),
    );
}

export const tagsKey = (projectId: string) => ['tags', projectId] as const;

export function listTags(projectId: string) {
    return unwrap(scenarioApi.GET('/api/projects/{projectId}/tags', {params: {path: {projectId}}}));
}

/** AC-5.4: a new feature's starting source, from its file name ("refund.feature" → "Feature: Refund\n"). */
export function starterSource(path: string): string {
    const file = path.split('/').pop() ?? path;
    const base = file.replace(/\.feature$/i, '').replace(/[-_]+/g, ' ').trim();
    const title = base ? base.charAt(0).toUpperCase() + base.slice(1) : 'New feature';
    return `Feature: ${title}\n`;
}
