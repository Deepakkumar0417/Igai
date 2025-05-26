# IGAI

## Description
IGAI is a React-based project built using Vite. This project provides a fast and efficient development environment with modern tooling.

## Installation

Follow these steps to set up and run the project:

### Prerequisites
Make sure you have the following installed:
- [Node.js](https://nodejs.org/) (Recommended: LTS version)
- [npm](https://www.npmjs.com/)

### Clone the Repository
```sh
git clone https://github.com/your-username/igai.git
cd igai
```

### Install Dependencies
```sh
npm install
```

## Environment Variables
Before running the project, create a `.env` file in the root directory and add the following variables:
```
VITE_API_URL=http://localhost:9093
VITE_AI_API_URL=http://localhost:5550
VITE_AI_SOCKET_URL=http://localhost:5560
```

## Running the Project
To start the development server, run:
```sh
npm run dev
```

## Build for Production
To create a production build:
```sh
npm run build
```

## Running the Production Build
To preview the production build locally:
```sh
npm run preview
```