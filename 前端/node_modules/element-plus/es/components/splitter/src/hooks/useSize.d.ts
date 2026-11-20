import { PanelItemState } from "../type.js";
import { ComputedRef, Ref } from "vue";

//#region ../../packages/components/splitter/src/hooks/useSize.d.ts
declare function useSize(panels: Ref<PanelItemState[]>, containerSize: ComputedRef<number>): {
  percentSizes: Ref<number[], number[]>;
  pxSizes: ComputedRef<number[]>;
};
//#endregion
export { useSize };