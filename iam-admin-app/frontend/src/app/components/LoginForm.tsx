import { useState, useEffect } from 'react';
import { Button } from '@/app/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/app/components/ui/card';
import { Alert, AlertDescription } from '@/app/components/ui/alert';
import { LogIn, AlertCircle } from 'lucide-react';
import { Header } from '@/app/components/Header';
import { Footer } from '@/app/components/Footer';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { apiUrl } from '@/lib/api';
import { AuthErrorPayload, resolveAuthErrorMessage } from '@/lib/authError';

export function LoginForm() {
  const { t, language } = useLanguage();
  const params = new URLSearchParams(window.location.search);
  const hasLoginError = params.has('loginError');
  const hasSessionExpired = params.has('sessionExpired');
  // The whole payload is kept, not a finished sentence: the message is resolved on every render so
  // switching language on the login page also switches the alert that is already on screen.
  const [authError, setAuthError] = useState<AuthErrorPayload | null>(null);

  useEffect(() => {
    if (!hasLoginError) return;
    fetch(apiUrl('api/auth-error'))
      .then((r) => (r.ok ? r.json() : null))
      .then((data: unknown) => {
        setAuthError(data as AuthErrorPayload | null);
      })
      .catch(() => {
        // ignore — generic message will be shown
      });
  }, [hasLoginError]);

  const errorDescription = resolveAuthErrorMessage(authError, language);

  const handleLogin = () => {
    window.location.href = apiUrl('oauth2/authorization/iam-admin');
  };

  return (
    <div className="min-h-screen flex flex-col">
      <Header />

      <div className="flex-1 flex items-center justify-center p-4">
        <Card className="w-full max-w-2xl shadow-lg">
          <CardHeader>
            <CardTitle className="text-2xl">{t('login.title')}</CardTitle>
            <CardDescription className="text-base">
              {t('login.description')}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {hasSessionExpired && (
                <Alert variant="destructive">
                  <AlertCircle className="h-4 w-4" />
                  <AlertDescription>
                    {t('login.sessionExpired')}
                  </AlertDescription>
                </Alert>
              )}
              {hasLoginError && (
                <Alert variant="destructive">
                  <AlertCircle className="h-4 w-4" />
                  <AlertDescription>
                    {errorDescription ?? t('login.accessDenied')}
                  </AlertDescription>
                </Alert>
              )}
              <Button
                onClick={handleLogin}
                className="w-full bg-primary hover:bg-primary/90"
                size="lg"
              >
                <LogIn className="w-5 h-5 mr-2" />
                {t('login.button')}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>

      <Footer />
    </div>
  );
}
