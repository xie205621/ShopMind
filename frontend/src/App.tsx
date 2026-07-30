/* ============================================================
   App.tsx — Root component with Router + Sidebar Navigation
   SAD.md §1.3, §3.1, §8.1
   ============================================================ */

import { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, NavLink, Navigate } from 'react-router-dom';
import { ConfigProvider, theme as antTheme } from 'antd';
import { MessageOutlined, DashboardOutlined } from '@ant-design/icons';
import { PageLoader } from './shared/components/PageLoader';
import styles from './App.module.css';

// ── Lazy-loaded pages (SAD §9.2: Route Lazy Loading mandatory) ──
const ChatPage = lazy(() => import('./pages/ChatPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));

// ── Ant Design dark theme config ──
const darkTheme = {
  algorithm: antTheme.darkAlgorithm,
  token: {
    colorBgContainer: '#111620',
    colorBgElevated: '#161c28',
    colorBorder: '#1a2232',
    colorBorderSecondary: '#243049',
    colorPrimary: '#3b82f6',
    colorText: '#e8ecf1',
    colorTextSecondary: '#8896a7',
    colorTextTertiary: '#566477',
    fontFamily: "'Inter', -apple-system, sans-serif",
    borderRadius: 10,
  },
};

/** Minimal sidebar — SAD.md §3.1: w=56px, bg-root, icon-only nav */
function Sidebar() {
  return (
    <nav className={styles.sidebar}>
      <NavLink
        to="/"
        end
        className={({ isActive }) =>
          `${styles.navItem} ${isActive ? styles.navItemActive : ''}`
        }
        title="Chat"
      >
        <MessageOutlined />
      </NavLink>
      <NavLink
        to="/dashboard"
        className={({ isActive }) =>
          `${styles.navItem} ${isActive ? styles.navItemActive : ''}`
        }
        title="Dashboard"
      >
        <DashboardOutlined />
      </NavLink>
    </nav>
  );
}

export default function App() {
  return (
    <ConfigProvider theme={darkTheme}>
      <BrowserRouter>
        <div className={styles.layout}>
          <Sidebar />
          <main className={styles.content}>
            <Suspense fallback={<PageLoader />}>
              <Routes>
                <Route path="/" element={<ChatPage />} />
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </Suspense>
          </main>
        </div>
      </BrowserRouter>
    </ConfigProvider>
  );
}
