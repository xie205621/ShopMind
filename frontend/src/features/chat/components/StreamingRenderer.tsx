/* ============================================================
   StreamingRenderer — SAD.md §2.1, FRONTEND_UI_BLUEPRINT.md §3.2
   Renders markdown content with typewriter cursor during streaming.
   When complete: full markdown render (react-markdown + remark-gfm + rehype-highlight).
   ============================================================ */

import { memo, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import styles from './StreamingRenderer.module.css';

interface StreamingRendererProps {
  text: string;
  isComplete: boolean;
}

export const StreamingRenderer = memo(function StreamingRenderer({
  text,
  isComplete,
}: StreamingRendererProps) {
  if (!text && !isComplete) {
    return <span className={styles.cursor}>█</span>;
  }

  if (!isComplete) {
    // Streaming mode: plain text + cursor, no markdown (avoids broken syntax)
    return (
      <span className={styles.streaming}>
        {text}
        <span className={styles.cursor}>█</span>
      </span>
    );
  }

  // Complete mode: full markdown render
  return (
    <div className={styles.markdown}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          // Override default elements to use our CSS classes
          code: ({ className, children, ...props }) => {
            const isInline = !className;
            if (isInline) {
              return (
                <code className={styles.inlineCode} {...props}>
                  {children}
                </code>
              );
            }
            return (
              <code className={className} {...props}>
                {children}
              </code>
            );
          },
          blockquote: ({ children }) => (
            <blockquote className={styles.blockquote}>{children}</blockquote>
          ),
          table: ({ children }) => (
            <div className={styles.tableWrapper}>
              <table className={styles.table}>{children}</table>
            </div>
          ),
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noopener noreferrer" className={styles.link}>
              {children}
            </a>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
});
