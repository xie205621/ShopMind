/* ============================================================
   PromptInput — SAD.md §2.1, FRONTEND_UI_BLUEPRINT.md §3.2
   bg-input, h=48px, radius-md.
   Enter=send, Shift+Enter=newline, disabled during streaming.
   ============================================================ */

import { memo, useState, useCallback, type KeyboardEvent } from 'react';
import styles from './PromptInput.module.css';

interface PromptInputProps {
  disabled: boolean;
  onSend: (text: string) => void;
  placeholder?: string;
}

export const PromptInput = memo(function PromptInput({
  disabled,
  onSend,
  placeholder = 'Type your question...',
}: PromptInputProps) {
  const [value, setValue] = useState('');

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!disabled && value.trim()) {
          onSend(value);
          setValue('');
        }
      }
    },
    [disabled, value, onSend],
  );

  const handleSendClick = useCallback(() => {
    if (!disabled && value.trim()) {
      onSend(value);
      setValue('');
    }
  }, [disabled, value, onSend]);

  return (
    <div className={styles.wrapper}>
      <textarea
        className={styles.input}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        disabled={disabled}
        rows={1}
        autoFocus
      />
      <button
        className={styles.sendBtn}
        onClick={handleSendClick}
        disabled={disabled || !value.trim()}
      >
        Send
      </button>
      <div className={styles.hint}>
        Shift + Enter for newline · ShopMind v2.1
      </div>
    </div>
  );
});
