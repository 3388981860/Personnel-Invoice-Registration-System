import { CSSProperties } from "vue";

//#region ../../packages/utils/dom/style.d.ts
declare const classNameToArray: (cls?: string) => string[];
declare const hasClass: (el: Element, cls: string) => boolean;
declare const addClass: (el: Element, cls: string) => void;
declare const removeClass: (el: Element, cls: string) => void;
declare const getStyle: (element: HTMLElement, styleName: keyof CSSProperties) => string;
declare const setStyle: (element: HTMLElement, styleName: CSSProperties | keyof CSSProperties, value?: string | number) => void;
declare const removeStyle: (element: HTMLElement, style: CSSProperties | keyof CSSProperties) => void;
declare function addUnit(value?: string | number, defaultUnit?: string): string | undefined;
/** Converts a percentage string to a ratio, e.g. '50%' to 0.5. */
declare function percentToRatio(value: string): number;
/** Parses the numeric value of a pixel string, e.g. '100px' to 100. */
declare function parsePx(value: string): number;
/** Checks for a '%' suffix without validating the numeric value. */
declare function isPercent(value: string | number | undefined): value is string;
/** Checks for a 'px' suffix without validating the numeric value. */
declare function isPx(value: string | number | undefined): value is string;
//#endregion
export { addClass, addUnit, classNameToArray, getStyle, hasClass, isPercent, isPx, parsePx, percentToRatio, removeClass, removeStyle, setStyle };