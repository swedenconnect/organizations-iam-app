/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createContext, useContext, useEffect, useState } from 'react';

interface ThemeData {
  mode: string;
  logoUrl: string;
  footerLogoUrl: string;
  logoHeight: string;
  footerLogoHeight: string;
}

const defaultTheme: ThemeData = {
  mode: '',
  logoUrl: '',
  footerLogoUrl: '',
  logoHeight: 'h-12',
  footerLogoHeight: 'h-8',
};

const ThemeContext = createContext<ThemeData>(defaultTheme);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<ThemeData>(defaultTheme);

  useEffect(() => {
    fetch('/api/theme')
      .then((r) => r.json())
      .then((data: { mode: string; logoUrl: string; footerLogoUrl: string; logoHeight: string; footerLogoHeight: string }) => {
        const link = document.createElement('link');
        link.id = 'theme-css';
        link.rel = 'stylesheet';
        link.href = '/theme/theme.css';
        if (!document.getElementById('theme-css')) {
          document.head.appendChild(link);
        }
        setTheme({
          mode: data.mode,
          logoUrl: data.logoUrl,
          footerLogoUrl: data.footerLogoUrl,
          logoHeight: data.logoHeight ?? 'h-12',
          footerLogoHeight: data.footerLogoHeight ?? 'h-8',
        });
      })
      .catch(() => {});
  }, []);

  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  return useContext(ThemeContext);
}
