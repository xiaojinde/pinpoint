import { Component, OnInit, OnChanges, ViewChild, ElementRef, SimpleChanges, Input, Output, EventEmitter, HostBinding } from '@angular/core';
import bb, { PrimitiveArray } from 'billboard.js';

@Component({
    selector: 'pp-load-chart',
    templateUrl: './load-chart.component.html',
    styleUrls: ['./load-chart.component.css']
})
export class LoadChartComponent implements OnInit, OnChanges {
    @HostBinding('class') hostClass = 'l-load-chart';
    @ViewChild('chartHolder') chartHolder: ElementRef;
    @Input() chartConfig: IChartConfig;
    @Output() outRendered = new EventEmitter<void>(true);

    private chartInstance: any;

    constructor() {}
    ngOnInit() {}
    ngOnChanges(changes: SimpleChanges) {
        Object.keys(changes)
            .filter((propName: string) => {
                return changes[propName].currentValue;
            })
            .forEach((propName: string) => {
                const changedProp = changes[propName];

                switch (propName) {
                    case 'chartConfig':
                        this.chartInstance ? this.updateChart(changedProp) : this.initChart();
                        break;
                }
            });
    }

    private initChart(): void {
        this.chartInstance = bb.generate({
            bindto: this.chartHolder.nativeElement,
            data: this.chartConfig.dataConfig,
            ...this.chartConfig.elseConfig,
            onrendered: () => this.finishLoading(),
        });
    }

    private updateChart({previousValue, currentValue}: {previousValue: IChartConfig, currentValue: IChartConfig}): void {
        const {columns: prevColumns} = previousValue.dataConfig;
        const {columns: currColumns, colors} = currentValue.dataConfig;
        const prevKeys = prevColumns.map(([key]: PrimitiveArray) => key);
        const currKeys = currColumns.map(([key]: PrimitiveArray) => key);
        const removedKeys = prevKeys.filter((key: string) => !currKeys.includes(key));
        const {axis: {y, y2 = {}}} = currentValue.elseConfig;
        /**
         * About determining "unload":
         * 1. If there was no data before => nothing to unload
         * 2. If something has changed in keys => unload all
         * 3. If there is no change in keys => determine it with "getEmptyDataKeys" method
         */
        const unload = prevKeys.length === 0 ? false
            : removedKeys.length !== 0 ? true
            : this.getEmptyDataKeys(currColumns);

        this.chartInstance.config('data.groups', [currKeys.slice(1)]);
        this.chartInstance.load({
            columns: currColumns,
            colors,
            unload
        });

        this.chartInstance.axis.max({
            y: y.max,
            y2: y2.max
        });
    }

    private getEmptyDataKeys(data: PrimitiveArray[]): string[] {
        return data.slice(1).filter((d: PrimitiveArray) => d.length === 1).map(([key]: PrimitiveArray) => key as string);
    }

    private finishLoading(): void {
        this.outRendered.emit();
    }
}
