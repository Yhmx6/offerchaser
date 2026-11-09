'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

export type Politeness = 'polite' | 'assertive';
type Announce = (message: string, politeness?: Politeness) => void;

const AnnounceContext = createContext<Announce>(() => {});

/**
 * 全站两个常驻的读屏播报区：polite（状态变化）与 assertive（错误、409 冲突）。
 * 放在 app/layout.tsx 最外层，一次即可。
 *
 * 用法：
 *   const announce = useAnnounce();
 *   announce('运行状态：运行中');
 *   announce(error.message, 'assertive');
 */
export function LiveRegionProvider({ children }: { children: ReactNode }) {
  const [polite, setPolite] = useState('');
  const [assertive, setAssertive] = useState('');
  const flip = useRef(false);

  const announce = useCallback<Announce>((message, politeness = 'polite') => {
    // 连续两次相同文案不会触发播报：交替追加零宽字符让内容“变化”
    flip.current = !flip.current;
    const text = flip.current ? message : `${message}​`;
    if (politeness === 'assertive') setAssertive(text);
    else setPolite(text);
  }, []);

  return (
    <AnnounceContext.Provider value={announce}>
      {children}
      <div className="oc-sr" role="status" aria-live="polite" aria-atomic="true">
        {polite}
      </div>
      <div className="oc-sr" role="alert" aria-live="assertive" aria-atomic="true">
        {assertive}
      </div>
    </AnnounceContext.Provider>
  );
}

export function useAnnounce(): Announce {
  return useContext(AnnounceContext);
}

/**
 * 值变化时自动播报（首次渲染不播）。适合：运行状态、待办数量、当前空间。
 * 传 null / 空串表示暂时不播。
 *
 *   useAnnounceOnChange(`运行状态：${stateLabel}；待办 ${openCount} 项`);
 */
export function useAnnounceOnChange(message: string | null) {
  const announce = useAnnounce();
  const previous = useRef(message);

  useEffect(() => {
    if (message && message !== previous.current) announce(message);
    previous.current = message;
  }, [message, announce]);
}
