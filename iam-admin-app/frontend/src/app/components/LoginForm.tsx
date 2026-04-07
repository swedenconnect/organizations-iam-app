import { useState, useEffect } from 'react';
import { Button } from '@/app/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/app/components/ui/card';
import { Alert, AlertDescription } from '@/app/components/ui/alert';
import { LogIn, AlertCircle } from 'lucide-react';
import { Header } from '@/app/components/Header';
import { Footer } from '@/app/components/Footer';
import { useLanguage } from '@/app/contexts/LanguageContext';

export function LoginForm() {
  const { t } = useLanguage();
  const hasLoginError = new URLSearchParams(window.location.search).has('loginError');
  const [errorDescription, setErrorDescription] = useState<string | null>(null);

  useEffect(() => {
    if (!hasLoginError) return;
    fetch('/api/auth-error')
      .then((r) => (r.ok ? r.json() : {}))
      .then((data: { description?: string }) => {
        if (data.description) {
          setErrorDescription(data.description);
        }
      })
      .catch(() => {
        // ignore — generic message will be shown
      });
  }, [hasLoginError]);

  const handleLogin = () => {
    window.location.href = '/oauth2/authorization/iam-admin';
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
