import { useState, useEffect } from 'react';
import { Organization, FunctionType, OrganizationFunction } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/app/components/ui/dialog';
import { Checkbox } from '@/app/components/ui/checkbox';
import { useLanguage } from '@/app/contexts/LanguageContext';

interface AssignFunctionsDialogProps {
  open: boolean;
  onClose: () => void;
  organization: Organization | null;
  functions: FunctionType[];
  organizationFunctions: OrganizationFunction[];
  onAssignFunctionsToOrg: (organizationId: string, functionIds: string[]) => void;
}

export function AssignFunctionsDialog({
  open,
  onClose,
  organization,
  functions,
  organizationFunctions,
  onAssignFunctionsToOrg,
}: AssignFunctionsDialogProps) {
  const { t, language } = useLanguage();
  const [selectedFunctionIds, setSelectedFunctionIds] = useState<string[]>([]);

  const getOrgName = (org: Organization) => {
    return language === 'sv' ? org.nameSv : org.nameEn;
  };

  const alreadyAttachedIds = new Set(
    organization
      ? organizationFunctions
          .filter((of) => of.organizationId === organization.id)
          .map((of) => of.functionId)
      : []
  );

  useEffect(() => {
    if (organization) {
      setSelectedFunctionIds([]);
    }
  }, [organization, organizationFunctions]);

  const handleToggleFunction = (functionId: string) => {
    if (alreadyAttachedIds.has(functionId)) {
      return;
    }
    if (selectedFunctionIds.includes(functionId)) {
      setSelectedFunctionIds(selectedFunctionIds.filter((id) => id !== functionId));
    } else {
      setSelectedFunctionIds([...selectedFunctionIds, functionId]);
    }
  };

  const handleSave = () => {
    if (organization) {
      if (selectedFunctionIds.length === 0) {
        onClose();
      } else {
        onAssignFunctionsToOrg(organization.id, selectedFunctionIds);
        onClose();
      }
    }
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t('functions.assignToOrg')}</DialogTitle>
          {organization && (
            <p className="text-sm text-gray-500 mt-1">{getOrgName(organization)}</p>
          )}
        </DialogHeader>

        <div className="space-y-3 max-h-[400px] overflow-y-auto">
          {functions.length === 0 ? (
            <p className="text-sm text-gray-500 italic">
              No functions available. Create one first.
            </p>
          ) : (
            functions.map((func) => {
              const isAttached = alreadyAttachedIds.has(func.id);
              return (
                <div
                  key={func.id}
                  className={`flex items-start gap-3 p-3 border rounded ${isAttached ? 'opacity-60 cursor-not-allowed' : 'hover:bg-gray-50'}`}
                >
                  <Checkbox
                    id={`func-${func.id}`}
                    checked={isAttached || selectedFunctionIds.includes(func.id)}
                    disabled={isAttached}
                    onCheckedChange={() => handleToggleFunction(func.id)}
                  />
                  <label
                    htmlFor={`func-${func.id}`}
                    className={`flex-1 ${isAttached ? 'cursor-not-allowed' : 'cursor-pointer'}`}
                  >
                    <p className="text-sm font-medium">
                      {(language === 'sv' ? func.nameSv : func.nameEn) ||
                        (language === 'sv' ? func.nameEn : func.nameSv) ||
                        func.name}
                      {isAttached && (
                        <span className="ml-2 text-xs text-gray-400">{t('functions.alreadyAttached')}</span>
                      )}
                    </p>
                    <p className="text-xs text-gray-500">
                      {(language === 'sv' ? func.descriptionSv : func.descriptionEn) ||
                        (language === 'sv' ? func.descriptionEn : func.descriptionSv) ||
                        ''}
                    </p>
                  </label>
                </div>
              );
            })
          )}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="button" onClick={handleSave}>
            {t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}