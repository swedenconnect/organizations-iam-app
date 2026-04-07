import { useState, useEffect } from 'react';
import { ContactData } from '@/types';
import { fetchContactData, saveContactData } from '@/services';
import { Card, CardContent, CardHeader, CardTitle } from '@/app/components/ui/card';
import { Label } from '@/app/components/ui/label';
import { Input } from '@/app/components/ui/input';
import { Button } from '@/app/components/ui/button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/app/components/ui/alert-dialog';
import { toast } from 'sonner';

interface Props {
  orgId: string;
  right: 'read' | 'write' | 'admin';
}

export function ContactDataCard({ orgId, right }: Props) {
  const [data, setData] = useState<ContactData>({
    address: '',
    telephoneNumber: '',
    emailAddress: '',
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [errorDialog, setErrorDialog] = useState<string | null>(null);

  const isReadOnly = right === 'read';

  useEffect(() => {
    const pending = sessionStorage.getItem('pendingContactSave');
    if (pending) {
      try {
        const parsed = JSON.parse(pending) as { orgId: string };
        if (parsed.orgId === orgId) {
          setLoading(false);
          return;
        }
      } catch { /* fall through to fetch */ }
    }
    setLoading(true);
    fetchContactData(orgId)
      .then(setData)
      .catch(() => {
        setData({ address: '', telephoneNumber: '', emailAddress: '' });
      })
      .finally(() => setLoading(false));
  }, [orgId]);

  useEffect(() => {
    const raw = sessionStorage.getItem('pendingContactSave');
    if (!raw) return;
    try {
      const pending = JSON.parse(raw) as { orgId: string; data: ContactData };
      if (pending.orgId !== orgId) return;
      sessionStorage.removeItem('pendingContactSave');
      setData(pending.data);
      setSaving(true);
      saveContactData(orgId, pending.data)
        .then(() => toast.success('Contact information saved.'))
        .catch(() => setErrorDialog('Failed to save contact information. Please try again later.'))
        .finally(() => setSaving(false));
    } catch {
      sessionStorage.removeItem('pendingContactSave');
    }
  }, [orgId]);

  const handleSave = async () => {
    setSaving(true);
    sessionStorage.setItem('pendingContactSave', JSON.stringify({ orgId, data }));
    try {
      await saveContactData(orgId, data);
      sessionStorage.removeItem('pendingContactSave');
      toast.success('Contact information saved.');
    } catch {
      sessionStorage.removeItem('pendingContactSave');
      setErrorDialog('Failed to save contact information. Please try again later.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <Card>
        <CardContent className="py-6">
          <p className="text-sm text-muted-foreground">Loading...</p>
        </CardContent>
      </Card>
    );
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>Contact Information</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {isReadOnly && (
            <div className="rounded-md border border-muted bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
              You have read-only access. Contact an administrator to make changes.
            </div>
          )}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="address">Address</Label>
            <Input
              id="address"
              value={data.address}
              readOnly={isReadOnly}
              className={isReadOnly ? 'bg-muted/40 cursor-default' : undefined}
              onChange={(e) => setData((d) => ({ ...d, address: e.target.value }))}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="telephoneNumber">Telephone number</Label>
            <Input
              id="telephoneNumber"
              value={data.telephoneNumber}
              readOnly={isReadOnly}
              className={isReadOnly ? 'bg-muted/40 cursor-default' : undefined}
              onChange={(e) => setData((d) => ({ ...d, telephoneNumber: e.target.value }))}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="emailAddress">Email address</Label>
            <Input
              id="emailAddress"
              type="email"
              value={data.emailAddress}
              readOnly={isReadOnly}
              className={isReadOnly ? 'bg-muted/40 cursor-default' : undefined}
              onChange={(e) => setData((d) => ({ ...d, emailAddress: e.target.value }))}
            />
          </div>

          {!isReadOnly && (
            <Button
              onClick={handleSave}
              disabled={saving}
              className="bg-primary hover:bg-primary/90"
            >
              {saving ? 'Saving...' : 'Save'}
            </Button>
          )}
        </CardContent>
      </Card>

      <AlertDialog open={errorDialog !== null} onOpenChange={() => setErrorDialog(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Error</AlertDialogTitle>
            <AlertDialogDescription>{errorDialog}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction onClick={() => setErrorDialog(null)}>OK</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
