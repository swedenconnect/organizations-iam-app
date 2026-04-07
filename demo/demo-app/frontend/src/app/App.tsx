import { useState, useEffect } from 'react';
import { CurrentUser, OrgEntry } from '@/types';
import { fetchMe, fetchAdminUrl } from '@/services';
import { Header } from '@/app/components/Header';
import { LoginPage } from '@/app/components/LoginPage';
import { OrgPickerPage } from '@/app/components/OrgPickerPage';
import { ContactDataCard } from '@/app/components/ContactDataCard';
import { NoOrganizationsPage } from '@/app/components/NoOrganizationsPage';
import { Button } from '@/app/components/ui/button';
import { Toaster } from '@/app/components/ui/sonner';

export default function App() {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [selectedOrgId, setSelectedOrgId] = useState<string | null>(
    () => sessionStorage.getItem('selectedOrgId'),
  );

  const selectOrg = (orgId: string | null) => {
    if (orgId) {
      sessionStorage.setItem('selectedOrgId', orgId);
    } else {
      sessionStorage.removeItem('selectedOrgId');
    }
    setSelectedOrgId(orgId);
  };

  useEffect(() => {
    fetchMe()
      .then((user) => {
        setCurrentUser(user);
        if (user && user.organizations.length === 1) {
          selectOrg(user.organizations[0].orgId);
        }
      })
      .catch(() => {
        // not logged in
      })
      .finally(() => setAuthChecked(true));
  }, []);

  if (!authChecked) {
    return null;
  }

  if (!currentUser) {
    return <LoginPage />;
  }

  if (!selectedOrgId) {
    if (currentUser.organizations.length === 0) {
      return <NoOrganizationsPage userName={currentUser.name} />;
    }
    if (currentUser.organizations.length === 1) {
      return null;
    }
    return (
      <OrgPickerPage
        orgs={currentUser.organizations}
        onSelect={selectOrg}
        currentUser={currentUser}
      />
    );
  }

  const selectedOrg: OrgEntry | undefined = currentUser.organizations.find(
    (o) => o.orgId === selectedOrgId,
  );

  return (
    <div className="min-h-screen bg-background flex flex-col">
      <Toaster />
      <Header currentUser={currentUser} selectedOrg={selectedOrg} />

      <main className="flex-1 max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full">
        {selectedOrg ? (
          <>
            <p className="text-sm text-muted-foreground mb-4">
              This is a demo application illustrating how to integrate an application against
              the Organizations and Users IAM service. The contact data below is stored in the
              demo service and access is controlled by the rights granted in Keycloak.
            </p>

            <div className="mb-4">
              <h2 className="text-lg font-medium">{selectedOrg.orgName}</h2>
              <p className="text-sm text-muted-foreground">{selectedOrg.orgId}</p>
            </div>

            <ContactDataCard orgId={selectedOrg.orgId} right={selectedOrg.right} />

            <div className="mt-6 flex flex-col items-start gap-1">
              <Button
                variant="outline"
                onClick={async () => {
                  const url = await fetchAdminUrl(selectedOrg.orgId);
                  window.open(url, '_blank');
                }}
              >
                Delegate administration
              </Button>
              {selectedOrg.right !== 'admin' && (
                <p className="text-xs text-muted-foreground">
                  You need admin rights to delegate administration.
                </p>
              )}
            </div>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">No organization selected.</p>
        )}
      </main>
    </div>
  );
}
