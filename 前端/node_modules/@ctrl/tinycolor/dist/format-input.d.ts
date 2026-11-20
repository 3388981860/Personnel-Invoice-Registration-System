import { CMYK, HSL, HSLA, HSV, HSVA, Numberify, OKLCH, OKLCHA, RGB, RGBA } from './interfaces.js';
/**
 * Given a string or object, convert that input to RGB
 *
 * Possible string inputs:
 * ```
 * "red"
 * "#f00" or "f00"
 * "#ff0000" or "ff0000"
 * "#ff000000" or "ff000000"
 * "rgb 255 0 0" or "rgb (255, 0, 0)"
 * "rgb 1.0 0 0" or "rgb (1, 0, 0)"
 * "rgba (255, 0, 0, 1)" or "rgba 255, 0, 0, 1"
 * "rgba (1.0, 0, 0, 1)" or "rgba 1.0, 0, 0, 1"
 * "hsl(0, 100%, 50%)" or "hsl 0 100% 50%"
 * "hsla(0, 100%, 50%, 1)" or "hsla 0 100% 50%, 1"
 * "hsv(0, 100%, 100%)" or "hsv 0 100% 100%"
 * "cmyk(0, 20, 0, 0)" or "cmyk 0 20 0 0"
 * "oklch(62.8% 0.258 29.23)" or "oklch(0.628 0.258 29.23 / 50%)"
 * ```
 *
 * OKLCH input outside of sRGB is gamut mapped for `r`, `g` and `b`, and also returned as `oklch`
 */
export declare function inputToRGB(color: string | RGB | RGBA | HSL | HSLA | HSV | HSVA | OKLCH | OKLCHA | CMYK | any): {
    ok: boolean;
    format: any;
    r: number;
    g: number;
    b: number;
    a: number;
    oklch?: Numberify<OKLCH>;
};
/**
 * Permissive string parsing.  Take in a number of formats, and output an object
 * based on detected format.  Returns `{ r, g, b }` or `{ h, s, l }` or `{ h, s, v}` or `{ l, c, h }` or `{c, m, y, k}` or `{c, m, y, k, a}`
 */
export declare function stringInputToObject(color: string): any;
/**
 * Check to see if it looks like a CSS unit
 * (see `matchers` above for definition).
 */
export declare function isValidCSSUnit(color: string | number): boolean;
