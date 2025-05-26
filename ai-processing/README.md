# AI Processing - Python Server Setup Guide

## Prerequisites
Ensure you have the following installed before proceeding:
- Python **3.12.6**
- pip (comes with Python)
- Virtual Environment

---

## Setup Instructions

### 1. Install Python 3.12.6
#### **Windows**
1. Download Python **3.12.6** from [Python official site](https://www.python.org/downloads/).
2. Run the installer and check **"Add Python to PATH"** during installation.
3. Verify installation:
   ```sh
   python --version
   ```

### 2. Create a Virtual Environment

#### **Windows**
```sh
python -m venv venv
venv\Scripts\activate
```

#### **Mac/Linux**
```sh
python3 -m venv venv
source venv/bin/activate
```

Verify the virtual environment is activated by checking the prompt, which should now show `(venv)`.

---

### 3. Install Dependencies
```sh
pip install -r requirements.txt
```

---

### 4. Create a `.env` File
1. Navigate to the root directory of your project.
2. Create a file named `.env` and add the following variables:

```ini
AZURE_INFERENCE_CREDENTIAL_GPT=**********************
AZURE_INFERENCE_ENDPOINT_GPT=**********************
AZURE_INFERENCE_ENDPOINT_GPT4o=**********************
GRAPH_URI = **********************
GRAPH_USERNAME = **********************
GRAPH_PASSWORD = **********************
BACKEND_BASE_URL = **********************
GRAPH_DATABASE = **********************
```

---

### 5. Running the Application
```sh
python main.py
```


### 6. Additional Notes
- Make sure to **exclude** `.env` from version control by adding it to `.gitignore`.
- Always activate your virtual environment before running the server.
- Keep dependencies updated by running:
  ```sh
  pip install --upgrade -r requirements.txt
  ```

