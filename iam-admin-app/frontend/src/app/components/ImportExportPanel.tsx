import { useRef, useState } from 'react';
import { Button } from '@/app/components/ui/button';
import { Badge } from '@/app/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/app/components/ui/table';
import { Download, Upload, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { downloadExport, dryRunImport, confirmImport, cancelImport } from '@/services/index';
import type { ImportPreviewReport, ImportReport, ImportItemOutcome } from '@/types';

interface ImportExportPanelProps {
  /** Called after a successful confirm so the caller can reload organizations/users/functions. */
  onImportCompleted: () => void;
}

type Step = 'idle' | 'preview' | 'done';

function countByStatus(outcomes: ImportItemOutcome[], status: string): number {
  return outcomes.filter((o) => o.status === status).length;
}

function statusVariant(status: string): 'default' | 'secondary' | 'outline' | 'destructive' {
  switch (status) {
    case 'new':
    case 'created':
      return 'default';
    case 'skipped_duplicate':
      return 'outline';
    case 'error':
      return 'destructive';
    default:
      return 'secondary';
  }
}

/**
 * Superuser-only export/import panel. Import is a two-step flow: a dry-run validates the
 * uploaded file against the current realm state (without creating anything) and reports new
 * entries, duplicates and errors; a confirm step then creates the batch the dry-run produced.
 */
export function ImportExportPanel({ onImportCompleted }: ImportExportPanelProps) {
  const { t } = useLanguage();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState<Step>('idle');
  const [isExporting, setIsExporting] = useState(false);
  const [isBusy, setIsBusy] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportPreviewReport | null>(null);
  const [result, setResult] = useState<ImportReport | null>(null);

  const handleExport = async () => {
    setIsExporting(true);
    try {
      await downloadExport();
    } catch (error) {
      console.error('Error exporting data:', error);
      toast.error(t('importExport.error.export'));
    } finally {
      setIsExporting(false);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSelectedFile(e.target.files?.[0] ?? null);
  };

  const handleDryRun = async () => {
    if (!selectedFile) return;
    setIsBusy(true);
    try {
      const report = await dryRunImport(selectedFile);
      setPreview(report);
      setStep('preview');
    } catch (error) {
      console.error('Error running import dry-run:', error);
      const message = error instanceof Error ? error.message : '';
      toast.error(message && message !== 'DRY_RUN_FAILED' ? message : t('importExport.error.dryRun'));
    } finally {
      setIsBusy(false);
    }
  };

  const handleConfirm = async () => {
    if (!preview) return;
    setIsBusy(true);
    try {
      const report = await confirmImport(preview.batchId);
      setResult(report);
      setStep('done');
      onImportCompleted();
    } catch (error) {
      console.error('Error confirming import:', error);
      toast.error(t('importExport.error.confirm'));
    } finally {
      setIsBusy(false);
    }
  };

  const handleCancelPreview = async () => {
    if (preview) {
      try {
        await cancelImport(preview.batchId);
      } catch (error) {
        console.error('Error cancelling import batch:', error);
      }
    }
    reset();
  };

  const reset = () => {
    setStep('idle');
    setSelectedFile(null);
    setPreview(null);
    setResult(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const renderOutcomeTable = (title: string, outcomes: ImportItemOutcome[]) => {
    if (outcomes.length === 0) return null;
    return (
      <div className="space-y-2">
        <h4 className="text-sm font-medium">{title} ({outcomes.length})</h4>
        <div className="rounded-md border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('importExport.column.key')}</TableHead>
                <TableHead>{t('importExport.column.status')}</TableHead>
                <TableHead>{t('importExport.column.reason')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {outcomes.map((o, idx) => (
                <TableRow key={`${o.key}-${idx}`}>
                  <TableCell className="font-mono text-xs">{o.key}</TableCell>
                  <TableCell>
                    <Badge variant={statusVariant(o.status)}>{t(`importExport.status.${o.status}`)}</Badge>
                  </TableCell>
                  <TableCell className="text-xs text-gray-500">{o.reason ?? ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </div>
    );
  };

  return (
    <div className="space-y-6">
      <div className="rounded-md border p-4 space-y-3">
        <h3 className="font-medium">{t('importExport.exportTitle')}</h3>
        <p className="text-sm text-gray-500">{t('importExport.exportDescription')}</p>
        <Button variant="outline" onClick={handleExport} disabled={isExporting}>
          {isExporting ? (
            <Loader2 className="w-4 h-4 mr-2 animate-spin" />
          ) : (
            <Download className="w-4 h-4 mr-2" />
          )}
          {t('importExport.exportButton')}
        </Button>
      </div>

      <div className="rounded-md border p-4 space-y-4">
        <h3 className="font-medium">{t('importExport.importTitle')}</h3>
        <p className="text-sm text-gray-500">{t('importExport.importDescription')}</p>

        {step === 'idle' && (
          <div className="flex flex-wrap items-center gap-3">
            <input
              ref={fileInputRef}
              type="file"
              accept="application/json"
              onChange={handleFileChange}
              className="text-sm"
            />
            <Button onClick={handleDryRun} disabled={!selectedFile || isBusy}>
              {isBusy ? (
                <Loader2 className="w-4 h-4 mr-2 animate-spin" />
              ) : (
                <Upload className="w-4 h-4 mr-2" />
              )}
              {t('importExport.dryRunButton')}
            </Button>
          </div>
        )}

        {step === 'preview' && preview && (
          <div className="space-y-4">
            <p className="text-sm text-gray-500">{t('importExport.previewSummary')}</p>
            {renderOutcomeTable(t('importExport.section.functions'), preview.functions)}
            {renderOutcomeTable(t('importExport.section.organizations'), preview.organizations)}
            {renderOutcomeTable(t('importExport.section.users'), preview.users)}
            <div className="flex flex-wrap gap-2">
              <Button onClick={handleConfirm} disabled={isBusy}>
                {isBusy && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                {t('importExport.confirmButton')}
              </Button>
              <Button variant="outline" onClick={handleCancelPreview} disabled={isBusy}>
                {t('importExport.cancelButton')}
              </Button>
            </div>
          </div>
        )}

        {step === 'done' && result && (
          <div className="space-y-4">
            <p className="text-sm text-gray-500">
              {t('importExport.resultSummary')
                .replace('{functions}', String(countByStatus(result.functions, 'created')))
                .replace('{organizations}', String(countByStatus(result.organizations, 'created')))
                .replace('{users}', String(countByStatus(result.users, 'created')))}
            </p>
            {renderOutcomeTable(t('importExport.section.functions'), result.functions)}
            {renderOutcomeTable(t('importExport.section.organizations'), result.organizations)}
            {renderOutcomeTable(t('importExport.section.users'), result.users)}
            <Button variant="outline" onClick={reset}>
              {t('importExport.closeButton')}
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
