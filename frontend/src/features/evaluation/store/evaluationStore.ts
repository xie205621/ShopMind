/* ============================================================
   Evaluation Zustand Store — SAD.md §6
   Manages experiment list, current report, and comparison data.
   ============================================================ */

import { create } from 'zustand';
import type {
  ExperimentReport,
  ExperimentListItem,
  ComparisonReport,
} from '../../../shared/types/evaluation';

interface EvaluationState {
  // ── Experiment list ──
  experiments: ExperimentListItem[];
  experimentsLoading: boolean;
  experimentsError: string | null;

  // ── Current report (detail page) ──
  currentReport: ExperimentReport | null;
  reportLoading: boolean;
  reportError: string | null;

  // ── Comparison ──
  comparison: ComparisonReport | null;
  comparisonLoading: boolean;
  comparisonError: string | null;

  // ── Actions ──
  setExperiments: (data: ExperimentListItem[]) => void;
  setExperimentsLoading: (loading: boolean) => void;
  setExperimentsError: (error: string | null) => void;

  setCurrentReport: (report: ExperimentReport | null) => void;
  setReportLoading: (loading: boolean) => void;
  setReportError: (error: string | null) => void;

  setComparison: (data: ComparisonReport | null) => void;
  setComparisonLoading: (loading: boolean) => void;
  setComparisonError: (error: string | null) => void;

  clearAll: () => void;
}

const initialState = {
  experiments: [] as ExperimentListItem[],
  experimentsLoading: false,
  experimentsError: null as string | null,
  currentReport: null as ExperimentReport | null,
  reportLoading: false,
  reportError: null as string | null,
  comparison: null as ComparisonReport | null,
  comparisonLoading: false,
  comparisonError: null as string | null,
};

export const useEvaluationStore = create<EvaluationState>((set) => ({
  ...initialState,

  setExperiments: (data) => set({ experiments: data, experimentsError: null }),
  setExperimentsLoading: (loading) => set({ experimentsLoading: loading }),
  setExperimentsError: (error) => set({ experimentsError: error }),

  setCurrentReport: (report) => set({ currentReport: report, reportError: null }),
  setReportLoading: (loading) => set({ reportLoading: loading }),
  setReportError: (error) => set({ reportError: error }),

  setComparison: (data) => set({ comparison: data, comparisonError: null }),
  setComparisonLoading: (loading) => set({ comparisonLoading: loading }),
  setComparisonError: (error) => set({ comparisonError: error }),

  clearAll: () => set(initialState),
}));
