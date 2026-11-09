import {LoaderCircle} from 'lucide-react';

export function Spinner({size = 16}: { size?: 16 | 20 | 24 }) {
    return <LoaderCircle aria-hidden="true" size={size} className="animate-spin"/>;
}
