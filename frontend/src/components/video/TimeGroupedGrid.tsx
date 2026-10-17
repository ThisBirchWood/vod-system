import type { ReactNode } from "react";
import { groupByTimeRange } from "../../utils/utils";

type props<T> = {
    items: T[];
    getDate: (item: T) => string;
    renderItem: (item: T) => ReactNode;
}

const TimeGroupedGrid = <T,>({ items, getDate, renderItem }: props<T>) => {
    return (
        <div className="flex flex-col gap-8">
            {groupByTimeRange(items, getDate).map((group) => (
                <section key={group.label}>
                    <h2 className="text-xl font-heading text-text-primary mb-3">{group.label}</h2>
                    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
                        {group.items.map(renderItem)}
                    </div>
                </section>
            ))}
        </div>
    );
}

export default TimeGroupedGrid;
