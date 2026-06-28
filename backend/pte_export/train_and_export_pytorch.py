import argparse
import json
import random
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.optim as optim
from executorch.exir import to_edge_transform_and_lower
from sklearn.preprocessing import StandardScaler
from torch.export import export
from torch.utils.data import DataLoader, Dataset

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_DATA_DIR = SCRIPT_DIR / "data"
DEFAULT_OUTPUT_DIR = SCRIPT_DIR / "artifacts"

FEATURE_COLUMNS = [
    "baseline_resting_bpm",
    "baseline_avg_heart_rate",
    "baseline_daily_steps",
    "baseline_sleep_hours",
    "baseline_sleep_efficiency",
    "baseline_ecg_irregularity",
    "baseline_ecg_flagged_events",
    "current_resting_bpm",
    "current_avg_heart_rate",
    "current_daily_steps",
    "current_sleep_hours",
    "current_sleep_efficiency",
    "current_ecg_irregularity",
    "current_ecg_flagged_events",
    "delta_resting_bpm",
    "delta_avg_heart_rate",
    "delta_daily_steps",
    "delta_sleep_hours",
    "delta_sleep_efficiency",
    "delta_ecg_irregularity",
    "weekly_step_variability",
    "weekly_sleep_variability",
    "overnight_hr_mean",
    "daytime_hr_mean",
]

TARGET_COLUMNS = [
    "arrhythmia_score",
    "cardiac_decomp_score",
    "sleep_impairment_score",
]


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


class HealthRiskRegressor(nn.Module):
    def __init__(self, num_features: int) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(num_features, 32),
            nn.ReLU(),
            nn.Linear(32, 16),
            nn.ReLU(),
            nn.Linear(16, len(TARGET_COLUMNS)),
            nn.Sigmoid(),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class WatchHealthDataset(Dataset):
    def __init__(self, csv_file: Path, scaler: StandardScaler | None = None) -> None:
        frame = pd.read_csv(csv_file)
        missing = sorted((set(FEATURE_COLUMNS) | set(TARGET_COLUMNS)) - set(frame.columns))
        if missing:
            raise ValueError(f"{csv_file} is missing required columns: {missing}")

        features = frame[FEATURE_COLUMNS].to_numpy(dtype=np.float32)
        targets = frame[TARGET_COLUMNS].to_numpy(dtype=np.float32)
        if np.min(targets) < 0.0 or np.max(targets) > 1.0:
            raise ValueError(
                f"{csv_file} target columns must already be normalized to [0, 1] "
                f"but got min={targets.min():.4f}, max={targets.max():.4f}"
            )

        self.scaler = scaler or StandardScaler()
        if scaler is None:
            self.features = self.scaler.fit_transform(features).astype(np.float32)
        else:
            self.features = self.scaler.transform(features).astype(np.float32)
        self.targets = targets

    def __len__(self) -> int:
        return len(self.features)

    def __getitem__(self, idx: int) -> tuple[torch.Tensor, torch.Tensor]:
        return torch.from_numpy(self.features[idx]), torch.from_numpy(self.targets[idx])


def evaluate_model(
    model: nn.Module, loader: DataLoader, criterion: nn.Module
) -> tuple[float, float]:
    model.eval()
    total_loss = 0.0
    total_abs_error = 0.0
    total_rows = 0
    with torch.no_grad():
        for inputs, targets in loader:
            outputs = model(inputs)
            loss = criterion(outputs, targets)
            total_loss += loss.item() * inputs.size(0)
            total_abs_error += torch.abs(outputs - targets).sum().item()
            total_rows += inputs.size(0)
    mean_loss = total_loss / total_rows
    mean_abs_error = total_abs_error / (total_rows * len(TARGET_COLUMNS))
    return mean_loss, mean_abs_error


def train_model(
    data_dir: Path, output_dir: Path, epochs: int, batch_size: int, seed: int
) -> tuple[nn.Module, StandardScaler]:
    set_seed(seed)
    print("Loading datasets...")
    train_dataset = WatchHealthDataset(data_dir / "train.csv")
    test_dataset = WatchHealthDataset(data_dir / "test.csv", scaler=train_dataset.scaler)

    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True)
    test_loader = DataLoader(test_dataset, batch_size=batch_size, shuffle=False)

    model = HealthRiskRegressor(len(FEATURE_COLUMNS))
    criterion = nn.MSELoss()
    optimizer = optim.Adam(model.parameters(), lr=1e-3)

    print(f"Training PyTorch model for {epochs} epochs...")
    for epoch in range(epochs):
        model.train()
        total_loss = 0.0
        for inputs, targets in train_loader:
            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, targets)
            loss.backward()
            optimizer.step()
            total_loss += loss.item() * inputs.size(0)

        train_loss = total_loss / len(train_dataset)
        if (epoch + 1) % 10 == 0 or epoch == 0 or epoch + 1 == epochs:
            val_loss, val_mae = evaluate_model(model, test_loader, criterion)
            print(
                f"Epoch {epoch + 1:02d}/{epochs} | "
                f"train_mse={train_loss:.6f} | val_mse={val_loss:.6f} | val_mae={val_mae:.6f}"
            )

    output_dir.mkdir(parents=True, exist_ok=True)
    metadata = {
        "feature_columns": FEATURE_COLUMNS,
        "target_columns": TARGET_COLUMNS,
        "scaler_mean": train_dataset.scaler.mean_.tolist(),
        "scaler_scale": train_dataset.scaler.scale_.tolist(),
        "seed": seed,
        "epochs": epochs,
        "batch_size": batch_size,
    }
    with open(output_dir / "pytorch_metadata.json", "w") as f:
        json.dump(metadata, f, indent=2)

    return model, train_dataset.scaler


def export_to_executorch(model: nn.Module, output_dir: Path) -> Path:
    print("\nExporting model to ExecuTorch (.pte)...")
    model.eval()
    example_inputs = (torch.randn(1, len(FEATURE_COLUMNS)),)

    print("1. Tracing via torch.export...")
    exported_program = export(model, example_inputs)

    print("2. Lowering to ExecuTorch edge dialect...")
    edge_program = to_edge_transform_and_lower(exported_program)

    print("3. Saving to .pte format...")
    output_path = output_dir / "health_risk_model.pte"
    with open(output_path, "wb") as f:
        f.write(edge_program.to_executorch().buffer)

    print(f"Successfully saved ExecuTorch model to: {output_path}")
    return output_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train the watch health PyTorch regressor and export it to ExecuTorch .pte."
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=DEFAULT_DATA_DIR,
        help="Directory containing train.csv and test.csv.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="Directory where .pt/.pte/metadata files are written.",
    )
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--seed", type=int, default=7)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    model, _ = train_model(
        data_dir=args.data_dir,
        output_dir=args.output_dir,
        epochs=args.epochs,
        batch_size=args.batch_size,
        seed=args.seed,
    )
    torch.save(model.state_dict(), args.output_dir / "pytorch_model.pt")
    export_to_executorch(model, args.output_dir)


if __name__ == "__main__":
    main()
