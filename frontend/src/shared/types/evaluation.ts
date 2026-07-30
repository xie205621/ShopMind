/* ============================================================
   Evaluation domain types — SAD.md §2.2, §2.3, §5
   Must match backend domain classes exactly.
   ============================================================ */

// ── Metric Summary (8-dimension) ──
export interface MetricSummary {
  intentAccuracy: number;
  avgRecallAtK: number;
  hallucinationRate: number;
  toolAccuracy: number;
  taskSuccessRate: number;
  avgTtftMs: number;
  p95LatencyMs: number;
  workflowCompletionRate: number;
}

// ── Cost Summary ──
export interface CostSummary {
  totalPromptTokens: number;
  totalCompletionTokens: number;
  estimatedCostUsd: number;
  pricingModel: string;
}

// ── Failure Reason ──
export type FailureReason =
  | 'WRONG_INTENT'
  | 'WRONG_TOOL'
  | 'WRONG_PARAMETER'
  | 'KNOWLEDGE_MISS'
  | 'HALLUCINATION'
  | 'SAFETY_BLOCKED'
  | 'TIMEOUT';

// ── Failure Distribution ──
export type FailureDistribution = Record<FailureReason, number>;

// ── Failed Case Detail ──
export interface FailedCaseDetail {
  testCaseId: string;
  query: string;
  reason: FailureReason;
  actualResponse: string;
  diagnostics: string;
}

// ── Experiment Report ──
export interface ExperimentReport {
  experimentId: string;
  workflowVersion: string;
  datasetVersion: string;
  generatedAt: string;
  totalCases: number;
  passedCases: number;
  metrics: MetricSummary;
  cost: CostSummary;
  failureDistribution: FailureDistribution;
  failedDetails: FailedCaseDetail[];
  config?: ExperimentConfig;
}

// ── Experiment Config (metadata) ──
export interface ExperimentConfig {
  workflowVersion: string;
  datasetVersion: string;
  llmModel: string;
  embeddingModel: string;
  vectorStoreType: string;
  maxConcurrency: number;
  rpmLimit: number;
  temperature: number;
  topP: number;
  maxTokens: number;
}

// ── Experiment List Item (table row) ──
export interface ExperimentListItem {
  experimentId: string;
  workflowVersion: string;
  datasetVersion: string;
  generatedAt: string;
  totalCases: number;
  passedCases: number;
  metrics: MetricSummary;
  cost: CostSummary;
}

// ── A/B Comparison Dimension ──
export interface ComparisonDimension {
  name: string;
  baseline: number;
  current: number;
  delta: number;
  deltaPercent: number;
  unit: 'rate' | 'score' | 'ms' | '$';
}

// ── A/B Comparison Report ──
export interface ComparisonReport {
  baselineId: string;
  baselineLabel: string;
  currentId: string;
  currentLabel: string;
  dimensions: ComparisonDimension[];
}

// ── Metric Card Config (for MetricGrid rendering) ──
export interface MetricCardConfig {
  key: string;
  title: string;
  unit: string;
  icon: string;
  format: 'percent' | 'ms' | 'score' | 'dollar';
  lowerIsBetter: boolean;
  getValue: (m: MetricSummary) => number;
  getCostValue?: (c: CostSummary) => number;
}

// ── Loading / Error States ──
export type AsyncState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; error: string };
