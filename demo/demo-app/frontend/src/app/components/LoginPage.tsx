import { Button } from '@/app/components/ui/button';

export function LoginPage() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-background p-4">
      <div className="flex flex-col items-center gap-6 max-w-sm w-full">
        <h1 className="text-2xl font-medium text-center">Demo Application for IAM</h1>
        <Button
          onClick={() => { sessionStorage.removeItem('selectedOrgId'); window.location.href = '/oauth2/authorization/demo'; }}
          className="w-full"
          size="lg"
        >
          Log in
        </Button>
      </div>
    </div>
  );
}
