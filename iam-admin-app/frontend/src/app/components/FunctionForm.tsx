import { useState, useEffect } from 'react';
import { FunctionType } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Label } from '@/app/components/ui/label';
import { Textarea } from '@/app/components/ui/textarea';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/app/components/ui/dialog';
import { useLanguage } from '@/app/contexts/LanguageContext';

interface FunctionFormProps {
  func: FunctionType | null;
  isOpen: boolean;
  onClose: () => void;
  onSave: (func: Omit<FunctionType, 'id'> & { id?: string }) => void;
}

export function FunctionForm({ func, isOpen, onClose, onSave }: FunctionFormProps) {
  const { t } = useLanguage();
  const [name, setName] = useState('');
  const [nameSv, setNameSv] = useState('');
  const [nameEn, setNameEn] = useState('');
  const [descriptionSv, setDescriptionSv] = useState('');
  const [descriptionEn, setDescriptionEn] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (func) {
      setName(func.name);
      setNameSv(func.nameSv);
      setNameEn(func.nameEn);
      setDescriptionSv(func.descriptionSv);
      setDescriptionEn(func.descriptionEn);
    } else {
      setName('');
      setNameSv('');
      setNameEn('');
      setDescriptionSv('');
      setDescriptionEn('');
    }
    setFieldErrors({});
  }, [func, isOpen]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const errors: Record<string, string> = {};
    if (!func && !name.trim()) errors.name = t('validation.required');
    if (!nameSv.trim()) errors.nameSv = t('validation.required');
    if (!nameEn.trim()) errors.nameEn = t('validation.required');
    if (!descriptionSv.trim()) errors.descriptionSv = t('validation.required');
    if (!descriptionEn.trim()) errors.descriptionEn = t('validation.required');
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }
    setFieldErrors({});
    onSave({
      ...(func && { id: func.id }),
      name,
      nameSv,
      nameEn,
      descriptionSv,
      descriptionEn,
    });
  };

  return (
    <Dialog open={isOpen} onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            {func ? t('functions.edit') : t('functions.create')}
          </DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">

          {/* Identifier — create only */}
          {!func && (
            <div className="space-y-2">
              <Label htmlFor="name">{t('functions.name')} *</Label>
              <Input
                id="name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder=""
              />
              <p className="text-xs text-gray-500">{t('functions.nameHint')}</p>
              {fieldErrors.name && <p className="text-xs text-red-500">{fieldErrors.name}</p>}
            </div>
          )}

          {/* Swedish name */}
          <div className="space-y-2">
            <Label htmlFor="nameSv">{t('functions.nameSv')} *</Label>
            <Input
              id="nameSv"
              value={nameSv}
              onChange={(e) => setNameSv(e.target.value)}
              placeholder=""
            />
            {fieldErrors.nameSv && <p className="text-xs text-red-500">{fieldErrors.nameSv}</p>}
          </div>

          {/* English name */}
          <div className="space-y-2">
            <Label htmlFor="nameEn">{t('functions.nameEn')} *</Label>
            <Input
              id="nameEn"
              value={nameEn}
              onChange={(e) => setNameEn(e.target.value)}
              placeholder=""
            />
            {fieldErrors.nameEn && <p className="text-xs text-red-500">{fieldErrors.nameEn}</p>}
          </div>

          {/* Swedish description */}
          <div className="space-y-2">
            <Label htmlFor="descriptionSv">{t('functions.descriptionSv')} *</Label>
            <Textarea
              id="descriptionSv"
              value={descriptionSv}
              onChange={(e) => setDescriptionSv(e.target.value)}
              placeholder=""
              rows={3}
            />
            {fieldErrors.descriptionSv && <p className="text-xs text-red-500">{fieldErrors.descriptionSv}</p>}
          </div>

          {/* English description */}
          <div className="space-y-2">
            <Label htmlFor="descriptionEn">{t('functions.descriptionEn')} *</Label>
            <Textarea
              id="descriptionEn"
              value={descriptionEn}
              onChange={(e) => setDescriptionEn(e.target.value)}
              placeholder=""
              rows={3}
            />
            {fieldErrors.descriptionEn && <p className="text-xs text-red-500">{fieldErrors.descriptionEn}</p>}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit">
              {t('common.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
