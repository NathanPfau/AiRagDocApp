import logging
from fastapi import FastAPI
from app.routes.api import router

# Set logging level to DEBUG
logging.basicConfig(level=logging.DEBUG)

app = FastAPI()
app.include_router(router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=True)