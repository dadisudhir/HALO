# Health risk ExecuTorch export bundle

This folder contains the synthetic dataset, the PyTorch training/export script, and the generated ExecuTorch artifact for the HALO watch-health demo.

## Included files

- `train_and_export_pytorch.py` — retrains the PyTorch MLP and exports `.pte`
- `data/train.csv` and `data/test.csv` — training and evaluation splits
- `data/full_dataset.csv` — full synthetic dataset
- `artifacts/health_risk_model.pte` — generated ExecuTorch model
- `artifacts/pytorch_model.pt` — PyTorch weights
- `artifacts/pytorch_metadata.json` — feature order, scaler values, seed, training config
- `artifacts/pytorch_scaler.json` — saved feature normalization parameters
- `artifacts/metrics.json` — metrics from the original synthetic-data pipeline

## Windows usage

1. Clone this repo and checkout branch `backend`.
2. Create a Python environment with PyTorch, pandas, numpy, scikit-learn, and ExecuTorch installed.
3. Run:

```bash
python backend/pte_export/train_and_export_pytorch.py
```

This writes the rebuilt outputs into `backend/pte_export/artifacts/`.

## Android app asset

The current compiled model is also copied into:

`app/src/main/assets/models/health_risk_model.pte`

That is the file the Android app can bundle directly.
